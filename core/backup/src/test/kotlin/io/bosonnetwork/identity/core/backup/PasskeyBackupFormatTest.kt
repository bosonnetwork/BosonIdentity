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

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PasskeyBackupFormatTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `HKDF matches RFC 5869 test case 1`() {
        val okm = PasskeyBackupFormat.hkdfSha256(
            hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            hex("000102030405060708090a0b0c"),
            hex("f0f1f2f3f4f5f6f7f8f9"),
            42,
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun `HKDF matches RFC 5869 test case 3, with no salt and no info`() {
        val okm = PasskeyBackupFormat.hkdfSha256(hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), ByteArray(0), ByteArray(0), 42)
        assertArrayEquals(
            hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            okm,
        )
    }

    @Test
    fun `a sealed key opens with the same passkey's PRF, after a round trip through the stored bytes`() {
        val prf = ByteArray(32) { it.toByte() }
        val key = ByteArray(64) { (it * 7).toByte() }
        val sealed = PasskeyBackupFormat.seal(prf, byteArrayOf(1, 2, 3), "userA", key, SecureRandom())
        val read = PasskeyBackupFormat.decode(PasskeyBackupFormat.encode(sealed))
        assertEquals("userA", read.userId)
        assertArrayEquals(byteArrayOf(1, 2, 3), read.credentialId)
        assertArrayEquals(key, PasskeyBackupFormat.open(prf, read))
    }

    @Test(expected = AEADBadTagException::class)
    fun `another passkey's PRF does not open it`() {
        val sealed = PasskeyBackupFormat.seal(ByteArray(32) { 1 }, byteArrayOf(9), "userA", ByteArray(64))
        PasskeyBackupFormat.open(ByteArray(32) { 2 }, sealed)
    }

    @Test(expected = AEADBadTagException::class)
    fun `a backup relabelled for another identity does not open`() {
        val prf = ByteArray(32) { 3 }
        val sealed = PasskeyBackupFormat.seal(prf, byteArrayOf(9), "userA", ByteArray(64))
        PasskeyBackupFormat.open(prf, PasskeyBackupFormat.Sealed(sealed.credentialId, "userB", sealed.nonce, sealed.ciphertext))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bytes that are no backup are refused`() {
        PasskeyBackupFormat.decode(byteArrayOf(7, 7, 7))
    }

    @Test
    fun `the derivation strings are pinned`() {
        // Changing them would orphan every backup made so far.
        assertEquals("bosonnetwork.io", PasskeyBackupFormat.RP_ID)
        assertArrayEquals(
            java.security.MessageDigest.getInstance("SHA-256").digest("boson:prf:1:passkey-backup".toByteArray()),
            PasskeyBackupFormat.PRF_SALT,
        )
        val prf = ByteArray(32) { it.toByte() }
        assertArrayEquals(
            PasskeyBackupFormat.hkdfSha256(prf, ByteArray(0), "boson:kdf:1:aes256gcm:user-key-wrap".toByteArray(), 32),
            PasskeyBackupFormat.wrapKey(prf),
        )
    }
}
