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

package io.bosonnetwork.identity.feature.onboarding

import android.content.Context
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.identity.core.backup.PasskeyBackup
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.core.vault.KeyProtector
import io.bosonnetwork.identity.core.vault.Protection
import io.bosonnetwork.identity.core.vault.Sealed
import io.bosonnetwork.identity.core.vault.VaultStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Restoring from a passkey backup in onboarding, with the backup mocked. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingPasskeyRestoreTest {
    private class NoKeystore : KeyProtector {
        override fun isAvailable() = true
        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt) =
            Sealed(ByteArray(12), plaintext.copyOf())
        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt) = sealed.data.copyOf()
        override fun delete(alias: String) = Unit
    }

    private val dir: File = Files.createTempDirectory("onboarding").toFile()
    private val vault = IdentityVault(VaultStore(File(dir, "vault")), NoKeystore())
    private val backup = mockk<PasskeyBackup>()
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.onb_restore_passkey_failed) } returns "that passkey didn't open it"
    }
    private val key = Signature.KeyPair.random()
    private val userId = Id.of(key.publicKey().bytes()).toString()
    private val found = mockk<PasskeyBackup.Found> { every { userId } returns this@OnboardingPasskeyRestoreTest.userId }
    private lateinit var vm: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { backup.found() } returns listOf(found)
        vm = OnboardingViewModel(vault, backup, context)
    }

    @After
    fun tearDown() {
        runBlocking { vm.viewModelScope.coroutineContext.job.cancelAndJoin() }
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    @Test
    fun `backups in the store are offered, and one restores the identity as backed up`() = runTest {
        vm.startRestore()
        assertEquals(listOf(userId), vm.state.first { it.passkeyBackups.isNotEmpty() }.passkeyBackups)
        coEvery { backup.open(found) } returns key
        vm.restoreFromPasskey(userId)
        val state = vm.state.first { it.step == OnboardingStep.PROTECT }
        assertEquals(userId, state.userId)
        assertTrue(state.backupVerified)
    }

    @Test
    fun `a passkey that doesn't open it says so, and a dismissed sheet says nothing`() = runTest {
        vm.startRestore()
        vm.state.first { it.passkeyBackups.isNotEmpty() }
        coEvery { backup.open(found) } throws javax.crypto.AEADBadTagException()
        vm.restoreFromPasskey(userId)
        assertEquals("that passkey didn't open it", vm.state.first { it.error != null }.error)

        coEvery { backup.open(found) } throws AppError.AuthenticationCancelled("no")
        vm.restoreFromPasskey(userId)
        val state = vm.state.first { !it.busy }
        assertEquals(OnboardingStep.RESTORE, state.step)
        assertEquals(null, state.error)
    }

    @Test
    fun `a phone without Block Store just offers the other ways`() = runTest {
        coEvery { backup.found() } throws IllegalStateException("no Play services")
        vm.startRestore()
        assertTrue(vm.state.first { it.step == OnboardingStep.RESTORE }.passkeyBackups.isEmpty())
    }
}
