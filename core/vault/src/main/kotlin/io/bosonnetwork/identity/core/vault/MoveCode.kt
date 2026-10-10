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

import io.bosonnetwork.identity.core.model.LocalProfile
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.model.ProfileFields
import io.bosonnetwork.utils.Base58
import io.bosonnetwork.utils.BosonString
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject

/**
 * What moves to a new phone: the identity's key, its home node if it has one, whether its backup was
 * checked, and its profile (not the avatar, which doesn't fit in a QR code; with a home node, the new phone
 * fetches it).
 */
class MovePayload(
    val privateKey: ByteArray,
    val homeNode: NodeAddress?,
    val backupVerified: Boolean,
    val profile: LocalProfile,
) {
    init {
        require(privateKey.size == 64) { "A user key is 64 bytes" }
    }
}

/**
 * Moving an identity to a new phone with one scan, and no node needed: the old phone shows a QR code,
 * the Boson string `boson:move:1:<salt>:<data>`, that holds the [MovePayload] encrypted under a code the
 * old phone generated and shows on its own, before the QR code; the user types the code on the new phone.
 *
 * - The code is generated, never chosen: 10 Crockford base32 characters, about 50 bits. A captured QR
 *   code can be tried against codes offline, so the code must be out of reach of guessing.
 * - The key is derived from it with Argon2id (64 MiB, 3 passes): every guess costs memory and time.
 * - The payload is encrypted with AES-256-GCM. `salt` is 16 bytes, `data` the 12-byte nonce followed by
 *   the ciphertext, both base64url without padding.
 *
 * The QR code holds only ciphertext, so the rule that no secret goes in a Boson string holds.
 */
object MoveCode {
    /** The Boson string namespace of a move code. */
    const val NAMESPACE = "move"
    const val VERSION = 1

    /** The characters of a code: Crockford base32, without I, L, O and U, which read like others. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val CODE_LENGTH = 10

    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BYTES = 32
    private const val MEMORY_KIB = 64 * 1024
    private const val PASSES = 3

    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** The text is not a move code. */
    class NotAMoveCode : Exception("Not a move code")

    /** The code typed doesn't open the move code. */
    class WrongCode : Exception("Wrong code")

    /** A new code, as it is shown and typed: `XXXX-XXXX-XX`. */
    fun newCode(): String = display(String(CharArray(CODE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }))

    /** [code] grouped for reading: `XXXX-XXXX-XX`. */
    fun display(code: String): String = code.chunked(4).joinToString("-")

    /**
     * The code as typed, made canonical: case, spaces and dashes ignored, and the letters people type for
     * digits (O for 0, I and L for 1) read as those; null when it can't be a code.
     */
    fun normalize(typed: String): String? {
        val code = typed.uppercase()
            .filterNot { it == '-' || it.isWhitespace() }
            .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }
            .joinToString("")
        return code.takeIf { it.length == CODE_LENGTH && it.all { c -> c in ALPHABET } }
    }

    /** Whether [text] is a move code (whatever its code). */
    fun isMoveCode(text: String): Boolean = BosonString.parse(text, NAMESPACE, VERSION, 2).isPresent

    /** Encrypts [payload] under [code] (as [newCode] makes it) into the text of the QR code. */
    fun seal(payload: MovePayload, code: String): String {
        val canonical = requireNotNull(normalize(code)) { "Not a code" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = derive(canonical, salt)
        val plain = encode(payload).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad())
            val data = nonce + cipher.doFinal(plain)
            return BosonString.format(NAMESPACE, VERSION, encoder.encodeToString(salt), encoder.encodeToString(data))
        } finally {
            key.fill(0)
            plain.fill(0)
        }
    }

    /**
     * Decrypts the QR code's [text] with the [typed] code.
     *
     * @throws NotAMoveCode if [text] is not a move code
     * @throws WrongCode if the code doesn't open it
     */
    fun open(text: String, typed: String): MovePayload {
        val parsed = BosonString.parse(text, NAMESPACE, VERSION, 2).orElseThrow { NotAMoveCode() }
        val code = normalize(typed) ?: throw WrongCode()
        val salt: ByteArray
        val data: ByteArray
        try {
            salt = decoder.decode(parsed.field(0))
            data = decoder.decode(parsed.field(1))
        } catch (e: IllegalArgumentException) {
            throw NotAMoveCode()
        }
        if (salt.size != SALT_BYTES || data.size <= NONCE_BYTES + 16) throw NotAMoveCode()
        val key = derive(code, salt)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, NONCE_BYTES))
            cipher.updateAAD(aad())
            cipher.doFinal(data, NONCE_BYTES, data.size - NONCE_BYTES)
        } catch (e: AEADBadTagException) {
            throw WrongCode()
        } finally {
            key.fill(0)
        }
        try {
            return decode(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw NotAMoveCode()
        } finally {
            plain.fill(0)
        }
    }

    // The ciphertext is bound to its format: the Boson string's head, as written.
    private fun aad(): ByteArray = "${BosonString.PREFIX}$NAMESPACE:$VERSION".toByteArray(Charsets.US_ASCII)

    private fun derive(code: String, salt: ByteArray): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(MEMORY_KIB)
            .withIterations(PASSES)
            .withParallelism(1)
            .build()
        val generator = Argon2BytesGenerator().apply { init(parameters) }
        return ByteArray(KEY_BYTES).also { generator.generateBytes(code.toByteArray(Charsets.US_ASCII), it) }
    }

    private fun encode(payload: MovePayload): String {
        val fields = payload.profile.fields
        return JSONObject()
            .put("key", Base58.encode(payload.privateKey))
            .putOpt("homeNode", payload.homeNode?.let { JSONObject().put("url", it.url).putOpt("nodeId", it.nodeId) })
            .put("backupVerified", payload.backupVerified)
            .put("profile", JSONObject()
                .putOpt("name", fields.name)
                .putOpt("bio", fields.bio)
                .putOpt("email", fields.email)
                .putOpt("revision", payload.profile.revision))
            .toString()
    }

    private fun decode(text: String): MovePayload {
        val json = JSONObject(text)
        val home = json.optJSONObject("homeNode")
        val profile = json.optJSONObject("profile") ?: JSONObject()
        fun JSONObject.string(name: String): String? = if (!has(name) || isNull(name)) null else getString(name)
        val fields = ProfileFields(name = profile.string("name"), bio = profile.string("bio"), email = profile.string("email"))
        // The revision is not carried as synced: the new phone syncs before trusting it, merging as a first sync.
        return MovePayload(
            privateKey = Base58.decode(json.getString("key")),
            homeNode = home?.let { NodeAddress(it.getString("url"), it.string("nodeId")) },
            backupVerified = json.optBoolean("backupVerified", false),
            profile = LocalProfile(fields),
        )
    }
}
