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

package io.bosonnetwork.identity.core.backup

import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.core.vault.KeyProtector
import io.bosonnetwork.identity.core.vault.Protection
import io.bosonnetwork.identity.core.vault.Sealed
import io.bosonnetwork.identity.core.vault.VaultStore
import java.io.File
import java.nio.file.Files
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The passkey backup end to end, with a stand-in passkey provider and store. */
class PasskeyBackupTest {

    private class NoKeystore : KeyProtector {
        override fun isAvailable() = true
        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt) =
            Sealed(ByteArray(12), plaintext.copyOf())
        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt) = sealed.data.copyOf()
        override fun delete(alias: String) = Unit
    }

    // A passkey provider whose passkeys each have their own PRF secret, as real ones do.
    private class FakePasskeys : PasskeyPrf {
        val secrets = mutableMapOf<String, ByteArray>()
        var createdFor: ByteArray? = null
        override fun isAvailable() = true
        override suspend fun create(userId: ByteArray, userName: String, displayName: String, salt: ByteArray): PrfResult {
            createdFor = userId
            val id = byteArrayOf(secrets.size.toByte(), 42)
            secrets[id.contentToString()] = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
            return evaluate(id, salt)
        }
        override suspend fun evaluate(credentialId: ByteArray?, salt: ByteArray): PrfResult {
            val secret = secrets[credentialId!!.contentToString()] ?: throw IllegalStateException("No such passkey")
            return PrfResult(credentialId, PasskeyBackupFormat.hkdfSha256(secret, salt, ByteArray(0), 32))
        }
    }

    private class FakeStore : BackupStore {
        val data = mutableMapOf<String, ByteArray>()
        override suspend fun isCloudBackupAvailable() = true
        override suspend fun put(key: String, bytes: ByteArray) { data[key] = bytes }
        override suspend fun get(key: String) = data[key]
        override suspend fun all() = data.toMap()
        override suspend fun delete(key: String) { data.remove(key) }
    }

    private val dir: File = Files.createTempDirectory("backup").toFile()
    private val prompt = AuthPrompt("Confirm")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun vault() = IdentityVault(VaultStore(File(dir, "vault-" + System.nanoTime())), NoKeystore())

    @Test
    fun `a backup made on one phone restores the identity on another`() = runTest {
        val passkeys = FakePasskeys()
        val store = FakeStore()
        val user = Signature.KeyPair.random()
        val userId = Id.of(user.publicKey().bytes()).toString()

        val oldPhone = vault().also { it.add(user, "Me", backupVerified = true, keepUserKey = true, prompt = prompt) }
        val backup = PasskeyBackup(oldPhone, passkeys, store)
        assertFalse(backup.isEnabled(userId))
        backup.enable(userId, "Me", prompt)
        assertTrue(backup.isEnabled(userId))
        assertArrayEquals(Id.of(userId).bytes, passkeys.createdFor)

        // The new phone has no identity, only the synced passkey and the restored store.
        val found = PasskeyBackup(vault(), passkeys, store).found().single()
        assertEquals(userId, found.userId)
        val restored = PasskeyBackup(vault(), passkeys, store).open(found)
        assertArrayEquals(user.privateKey().bytes(), restored.privateKey().bytes())
    }

    @Test
    fun `there is no backup before the identity's own backup is checked`() = runTest {
        val user = Signature.KeyPair.random()
        val userId = Id.of(user.publicKey().bytes()).toString()
        val vault = vault().also { it.add(user, null, backupVerified = false, keepUserKey = true, prompt = prompt) }
        val store = FakeStore()
        try {
            PasskeyBackup(vault, FakePasskeys(), store).enable(userId, null, prompt)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun `another passkey does not open the backup`() = runTest {
        val passkeys = FakePasskeys()
        val store = FakeStore()
        val user = Signature.KeyPair.random()
        val userId = Id.of(user.publicKey().bytes()).toString()
        val vault = vault().also { it.add(user, null, backupVerified = true, keepUserKey = true, prompt = prompt) }
        PasskeyBackup(vault, passkeys, store).enable(userId, null, prompt)
        val found = PasskeyBackup(vault, passkeys, store).found().single()

        // Replace the passkey's secret: as if the user picked another passkey.
        passkeys.secrets[found.sealed.credentialId.contentToString()] = ByteArray(32) { 5 }
        try {
            PasskeyBackup(vault, passkeys, store).open(found)
            fail("expected AEADBadTagException")
        } catch (e: AEADBadTagException) {
            // expected
        }
    }

    @Test
    fun `turning it off deletes the backup, and foreign entries are ignored`() = runTest {
        val store = FakeStore().also { it.data["someone.else"] = byteArrayOf(1, 2, 3) }
        val user = Signature.KeyPair.random()
        val userId = Id.of(user.publicKey().bytes()).toString()
        val vault = vault().also { it.add(user, null, backupVerified = true, keepUserKey = true, prompt = prompt) }
        val backup = PasskeyBackup(vault, FakePasskeys(), store)
        backup.enable(userId, null, prompt)
        assertEquals(1, backup.found().size)
        backup.disable(userId)
        assertFalse(backup.isEnabled(userId))
        assertTrue(backup.found().isEmpty())
    }
}
