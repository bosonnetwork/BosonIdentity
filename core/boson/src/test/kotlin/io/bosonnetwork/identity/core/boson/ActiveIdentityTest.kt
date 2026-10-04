/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.identity.core.boson

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.core.vault.KeyProtector
import io.bosonnetwork.identity.core.vault.Protection
import io.bosonnetwork.identity.core.vault.Sealed
import io.bosonnetwork.identity.core.vault.VaultStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The identity in use, over a real vault (with a stand-in for the Keystore) and a real DataStore. */
class ActiveIdentityTest {

    // Seals nothing: the selection logic never opens a key.
    private class NoKeystore : KeyProtector {
        override fun isAvailable() = true
        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt) =
            Sealed(ByteArray(12), plaintext.copyOf())
        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt) = sealed.data.copyOf()
        override fun delete(alias: String) = Unit
    }

    private val dir: File = Files.createTempDirectory("active").toFile()
    private val vault = IdentityVault(VaultStore(File(dir, "vault")), NoKeystore())
    private val storeJob = Job()

    private fun active(job: Job = storeJob) = ActiveIdentity(
        vault,
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { File(dir, "active.preferences_pb") },
    )

    private suspend fun addIdentity(): String =
        vault.add(Signature.KeyPair.random(), null, backupVerified = true, keepUserKey = true, prompt = AuthPrompt("Confirm")).userId

    @After
    fun cleanUp() {
        storeJob.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `no identity, nothing active`() = runTest {
        assertNull(active().active.first())
    }

    @Test
    fun `without a selection, the first identity is active`() = runTest {
        val first = addIdentity()
        addIdentity()
        assertEquals(first, active().active.first()?.userId)
    }

    @Test
    fun `the selected identity is active, also after a restart`() = runTest {
        addIdentity()
        val second = addIdentity()
        val firstRun = Job()
        val active = active(firstRun)
        active.select(second)
        assertEquals(second, active.active.first()?.userId)

        // A second DataStore on the same file may only open once the first one is gone.
        firstRun.cancelAndJoin()
        assertEquals(second, active().active.first()?.userId)
    }

    @Test
    fun `a removed selection falls back to the first identity`() = runTest {
        val first = addIdentity()
        val second = addIdentity()
        val active = active()
        active.select(second)
        assertEquals(second, active.active.first()?.userId)

        vault.remove(second)
        assertEquals(first, active.active.first()?.userId)
    }
}
