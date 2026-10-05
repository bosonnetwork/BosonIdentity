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

package io.bosonnetwork.identity.core.vault

import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.NodeAddress
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The vault's logic, with an in-memory AES stand-in for the Keystore that counts the prompts. */
class IdentityVaultTest {

    private class FakeProtector : KeyProtector {
        val keys = mutableMapOf<String, SecretKey>()
        val prompts = mutableListOf<Pair<Protection, String>>()
        var cancelNext = false

        override fun isAvailable() = true

        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt): Sealed {
            authorize(protection, prompt)
            val key = keys.getOrPut(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return Sealed(iv, cipher.doFinal(plaintext))
        }

        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt): ByteArray {
            val key = keys[alias] ?: throw AppError.KeyUnavailable("gone")
            authorize(protection, prompt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.iv)) }
            return cipher.doFinal(sealed.data)
        }

        override fun delete(alias: String) {
            keys.remove(alias)
        }

        private fun authorize(protection: Protection, prompt: AuthPrompt) {
            if (cancelNext) {
                cancelNext = false
                throw AppError.AuthenticationCancelled("cancelled")
            }
            prompts += protection to prompt.title
        }
    }

    private val dir: File = Files.createTempDirectory("vault").toFile()
    private val protector = FakeProtector()
    private val prompt = AuthPrompt("Confirm")

    private fun vault() = IdentityVault(VaultStore(dir), protector)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `an added identity's keys open again, from a fresh vault`() = runTest {
        val user = Signature.KeyPair.random()
        val userId = Id.of(user.publicKey().bytes()).toString()
        val info = vault().add(user, "Me", backupVerified = false, keepUserKey = true, prompt = prompt)
        assertEquals(userId, info.userId)

        val reopened = vault()
        reopened.load()
        assertEquals(listOf(userId), reopened.identities.value.map { it.userId })
        val signed = reopened.withUserKey(userId, prompt) { Signature.sign("hi".toByteArray(), it.privateKey()) }
        assertTrue(Signature.verify("hi".toByteArray(), signed, user.publicKey()))

        val device1 = reopened.deviceKey(userId, prompt)
        val device2 = vault().deviceKey(userId, prompt)
        assertArrayEquals(device1.publicKey().bytes(), device2.publicKey().bytes())
        assertFalse(device1.publicKey().bytes().contentEquals(user.publicKey().bytes()))
    }

    @Test
    fun `the user key is sealed for every use, the device key for a session`() = runTest {
        val user = Signature.KeyPair.random()
        val userId = Id.of(user.publicKey().bytes()).toString()
        val vault = vault()
        vault.add(user, null, backupVerified = true, keepUserKey = true, prompt = prompt)
        assertEquals(listOf(Protection.PER_USE, Protection.SESSION), protector.prompts.map { it.first })
        protector.prompts.clear()

        vault.withUserKey(userId, AuthPrompt("Approve")) { }
        vault.deviceKey(userId, AuthPrompt("Unlock"))
        assertEquals(listOf(Protection.PER_USE to "Approve", Protection.SESSION to "Unlock"), protector.prompts)
    }

    @Test
    fun `the key pair given to a block is destroyed after it`() = runTest {
        val user = Signature.KeyPair.random()
        val userId = Id.of(user.publicKey().bytes()).toString()
        val vault = vault()
        vault.add(user, null, backupVerified = true, keepUserKey = true, prompt = prompt)
        val leaked = vault.withUserKey(userId, prompt) { it }
        assertTrue(leaked.isDestroyed)
    }

    @Test
    fun `an identity whose key is not kept has no user key to use`() = runTest {
        val user = Signature.KeyPair.random()
        val userId = Id.of(user.publicKey().bytes()).toString()
        val vault = vault()
        vault.add(user, null, backupVerified = true, keepUserKey = false, prompt = prompt)
        assertFalse(vault.keepsUserKey(userId))
        try {
            vault.withUserKey(userId, prompt) { }
            fail("expected KeyUnavailable")
        } catch (e: AppError.KeyUnavailable) {
            // expected
        }
    }

    @Test
    fun `adding the same identity twice is refused`() = runTest {
        val user = Signature.KeyPair.random()
        val vault = vault()
        vault.add(user, null, backupVerified = true, keepUserKey = true, prompt = prompt)
        try {
            vault.add(user, null, backupVerified = true, keepUserKey = true, prompt = prompt)
            fail("expected Conflict")
        } catch (e: AppError.Conflict) {
            // expected
        }
    }

    @Test
    fun `a cancelled prompt adds nothing and leaves no keys behind`() = runTest {
        val vault = vault()
        protector.cancelNext = true
        try {
            vault.add(Signature.KeyPair.random(), null, backupVerified = true, keepUserKey = true, prompt = prompt)
            fail("expected AuthenticationCancelled")
        } catch (e: AppError.AuthenticationCancelled) {
            // expected
        }
        assertTrue(vault.identities.value.isEmpty())
        assertTrue(protector.keys.isEmpty())
    }

    @Test
    fun `updates are kept, and removing deletes the Keystore keys`() = runTest {
        val user = Signature.KeyPair.random()
        val userId = Id.of(user.publicKey().bytes()).toString()
        val vault = vault()
        vault.add(user, null, backupVerified = false, keepUserKey = true, prompt = prompt)
        vault.update(userId) {
            it.copy(backupVerified = true, homeNode = NodeAddress("https://node.example", "abc"), deviceId = "dev")
        }
        val reread = vault().also { it.load() }.identities.value.single()
        assertTrue(reread.backupVerified)
        assertEquals(NodeAddress("https://node.example", "abc"), reread.homeNode)
        assertEquals("dev", reread.deviceId)

        vault.remove(userId)
        assertTrue(protector.keys.isEmpty())
        assertTrue(vault().also { it.load() }.identities.value.isEmpty())
        assertNull(File(dir, "vault.json.tmp").takeIf { it.exists() })
    }

    @Test
    fun `a given device key is the one kept, and it can be replaced`() = runTest {
        val user = Signature.KeyPair.random()
        val userId = Id.of(user.publicKey().bytes()).toString()
        val registered = Signature.KeyPair.random()
        val vault = vault()
        vault.add(user, null, backupVerified = false, keepUserKey = true, prompt = prompt, deviceKey = registered)
        assertArrayEquals(registered.publicKey().bytes(), vault().deviceKey(userId, prompt).publicKey().bytes())

        val renewed = Signature.KeyPair.random()
        vault.replaceDeviceKey(userId, renewed, prompt)
        assertArrayEquals(renewed.publicKey().bytes(), vault().deviceKey(userId, prompt).publicKey().bytes())
        // The user key is untouched.
        val signed = vault().withUserKey(userId, prompt) { Signature.sign("x".toByteArray(), it.privateKey()) }
        assertTrue(Signature.verify("x".toByteArray(), signed, user.publicKey()))
    }
}
