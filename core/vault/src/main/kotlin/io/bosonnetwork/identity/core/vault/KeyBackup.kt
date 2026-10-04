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

import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.utils.Base58
import kotlin.random.Random

/**
 * The user key's backup, in the formats the rest of Boson already uses: the 64-byte private key
 * (seed || public key) as Base58, alone or in the key file the user portal writes. There is no word
 * phrase: the key itself is the backup.
 */
object KeyBackup {
    /** The key file's name, as the portal names it. */
    const val FILE_NAME = "boson-user-key.txt"

    /** The key's characters are shown, read and checked in groups of this size. */
    const val GROUP_SIZE = 4

    /** The key file, exactly as the user portal writes it, so either can read the other's. */
    fun keyFileText(privateKeyBase58: String, userId: String): String =
        "Boson User Identity Key\n\nprivateKey: $privateKeyBase58\npublicKey:  $userId\n\n" +
            "Keep your private key secret. Anyone with this value can act as you.\n"

    /**
     * Reads a key from what the user pasted, scanned or opened: a key file (its `privateKey:` line),
     * or the key itself as Base58 or hex (`0x` optional). Returns the validated 64-byte private key.
     *
     * @throws IllegalArgumentException if it is not a usable Ed25519 private key
     */
    fun parse(text: String): ByteArray {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("privateKey:") }
        val value = (line?.removePrefix("privateKey:") ?: text).trim()
        require(value.isNotEmpty()) { "Empty key" }
        val bytes = decode(value)
        require(bytes.size == Signature.PrivateKey.BYTES) {
            "A user key is ${Signature.PrivateKey.BYTES} bytes, not ${bytes.size}"
        }
        // The key is seed || public key. Boson builds the pair from the seed alone, so check here that
        // the public half is the seed's own: a damaged backup must not pass for a different identity.
        val derived = Signature.KeyPair.fromSeed(bytes.copyOfRange(0, Signature.KeyPair.SEED_BYTES))
        try {
            require(derived.publicKey().bytes().contentEquals(bytes.copyOfRange(Signature.KeyPair.SEED_BYTES, bytes.size))) {
                "The key's two halves don't belong together"
            }
        } finally {
            derived.destroy()
        }
        return bytes
    }

    // A 64-byte key is 128 hex characters; its Base58 is about 88, so the length tells them apart.
    private fun decode(value: String): ByteArray {
        val hex = value.removePrefix("0x").removePrefix("0X")
        return if (hex.length == 128 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            ByteArray(64) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } else {
            try {
                Base58.decode(value)
            } catch (e: RuntimeException) {
                throw IllegalArgumentException("Not a Base58 or hex key", e)
            }
        }
    }

    /** The Base58 text of a 64-byte private key. */
    fun encode(privateKey64: ByteArray): String = Base58.encode(privateKey64)

    /** The key split into groups of [GROUP_SIZE] characters, the last one possibly shorter. */
    fun groups(base58: String): List<String> = base58.chunked(GROUP_SIZE)

    /**
     * A check that the user saved the key, without retyping all of it: two different groups, chosen
     * at random, by their 0-based position.
     */
    data class Challenge(val first: Int, val second: Int) {
        /** The 1-based character range of a group, as shown to the user ("characters 13 to 16"). */
        fun range(group: Int, base58: String): IntRange {
            val start = group * GROUP_SIZE
            return (start + 1)..minOf(start + GROUP_SIZE, base58.length)
        }
    }

    /** Picks two different full groups of [base58] to ask for. */
    fun challenge(base58: String, random: Random = Random.Default): Challenge {
        val full = base58.length / GROUP_SIZE
        require(full >= 2) { "Key too short" }
        val first = random.nextInt(full)
        var second = random.nextInt(full - 1)
        if (second >= first) second++
        return if (first < second) Challenge(first, second) else Challenge(second, first)
    }

    /** Whether the two answers match the challenged groups. Base58 is case-sensitive; spaces are ignored. */
    fun verify(base58: String, challenge: Challenge, firstAnswer: String, secondAnswer: String): Boolean {
        val groups = groups(base58)
        fun clean(s: String) = s.filterNot { it.isWhitespace() }
        return groups[challenge.first] == clean(firstAnswer) && groups[challenge.second] == clean(secondAnswer)
    }
}
