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

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The passkey backup's cryptography and format, kept free of Android so it can be tested on its own.
 *
 * A passkey for [RP_ID] is asked for its PRF output with the constant [PRF_SALT]; HKDF-SHA256 turns that
 * into an AES-256-GCM key ([wrapKey]), which seals the 64-byte user key together with its user id. Only
 * the same passkey gives the same PRF output, so only it opens the backup.
 */
object PasskeyBackupFormat {
    /** The passkey belongs to the project's site, not to a node: it signs in nowhere, it only unlocks this backup. */
    const val RP_ID = "bosonnetwork.io"

    /** The PRF input, the same for every backup. */
    val PRF_SALT: ByteArray = sha256("boson:passkey:backup#v1".toByteArray(Charsets.US_ASCII))

    private val HKDF_INFO = "boson:backup:user-key#v1".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    /** A sealed backup: which passkey opens it, and for which identity. */
    class Sealed(val credentialId: ByteArray, val userId: String, val nonce: ByteArray, val ciphertext: ByteArray)

    /** The AES key the PRF output of the backup's passkey gives: HKDF-SHA256, empty salt, [HKDF_INFO]. */
    fun wrapKey(prf: ByteArray): ByteArray {
        require(prf.size >= 32) { "The PRF output is 32 bytes" }
        return hkdfSha256(prf, ByteArray(0), HKDF_INFO, 32)
    }

    /** Seals [privateKey64] of [userId] for the passkey [credentialId], whose PRF output is [prf]. */
    fun seal(prf: ByteArray, credentialId: ByteArray, userId: String, privateKey64: ByteArray, random: SecureRandom = SecureRandom()): Sealed {
        val key = wrapKey(prf)
        try {
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            // The user id is bound into the seal: a backup can't pass for another identity's.
            cipher.updateAAD(userId.toByteArray(Charsets.UTF_8))
            return Sealed(credentialId.clone(), userId, nonce, cipher.doFinal(privateKey64))
        } finally {
            key.fill(0)
        }
    }

    /**
     * Opens [sealed] with the PRF output of its passkey.
     *
     * @throws javax.crypto.AEADBadTagException if the passkey is not the backup's, or the backup was altered
     */
    fun open(prf: ByteArray, sealed: Sealed): ByteArray {
        val key = wrapKey(prf)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed.nonce))
            cipher.updateAAD(sealed.userId.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(sealed.ciphertext)
        } finally {
            key.fill(0)
        }
    }

    /** The bytes stored in Block Store. */
    fun encode(sealed: Sealed): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(VERSION)
            out.writeUTF(sealed.userId)
            out.writeShort(sealed.credentialId.size)
            out.write(sealed.credentialId)
            out.write(sealed.nonce)
            out.writeShort(sealed.ciphertext.size)
            out.write(sealed.ciphertext)
        }
        return bytes.toByteArray()
    }

    /** Reads stored bytes back; throws [IllegalArgumentException] for anything not a backup of this format. */
    fun decode(bytes: ByteArray): Sealed = try {
        DataInputStream(bytes.inputStream()).use { input ->
            require(input.readUnsignedByte() == VERSION) { "Unknown backup version" }
            val userId = input.readUTF()
            val credentialId = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
            val nonce = ByteArray(NONCE_BYTES).also { input.readFully(it) }
            val ciphertext = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
            require(input.read() == -1) { "Trailing bytes" }
            Sealed(credentialId, userId, nonce, ciphertext)
        }
    } catch (e: java.io.IOException) {
        throw IllegalArgumentException("Not a passkey backup", e)
    }

    /** RFC 5869 HKDF with SHA-256. */
    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        prk.fill(0)
        return out
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
