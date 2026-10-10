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

    /** How many groups a row of the key shows. */
    const val ROW_SIZE = 5

    /** A row of the key: the number of its first group (1-based), and its groups. */
    data class Row(val number: Int, val groups: List<String>)

    /** The key in rows of [ROW_SIZE] groups, numbered 1, 6, 11, ... by their first group. */
    fun rows(base58: String): List<Row> =
        groups(base58).chunked(ROW_SIZE).mapIndexed { i, groups -> Row(i * ROW_SIZE + 1, groups) }

    /** The rows as text: the number right-aligned, two spaces, then the groups. */
    fun gridLines(base58: String): List<String> {
        val rows = rows(base58)
        val width = rows.last().number.toString().length
        return rows.map { it.number.toString().padStart(width) + "  " + it.groups.joinToString(" ") }
    }

    /**
     * The key file, exactly as the user portal writes it, so either can read the other's: the key on its
     * `privateKey:` line (the only line either reads), and below it the same key in numbered groups, as
     * the app shows it, for checking or copying it by hand.
     */
    fun keyFileText(privateKeyBase58: String, userId: String): String =
        "Boson User Identity Key\n\nprivateKey: $privateKeyBase58\npublicKey:  $userId\n\n" +
            "The private key in numbered groups of four, to check or copy by hand:\n\n" +
            gridLines(privateKeyBase58).joinToString("") { "$it\n" } + "\n" +
            "Keep your private key secret. Anyone with this value can act as you.\n"

    /**
     * Reads a key from what the user pasted, scanned, opened or typed: a key file (its `privateKey:`
     * line), or the key itself as Base58 or hex (`0x` optional). Spaces and line breaks are ignored, and
     * so are the row numbers of the key as the app shows it (see [withoutRowNumbers]). Returns the
     * validated 64-byte private key.
     *
     * @throws IllegalArgumentException if it is not a usable Ed25519 private key
     */
    fun parse(text: String): ByteArray {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("privateKey:") }
        val value = withoutRowNumbers(line?.removePrefix("privateKey:") ?: text).joinToString("")
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

    /**
     * The words of a typed key, without the numbers of its rows if it was copied from the grid: a word is
     * taken for a row number only where the grid puts one, before the first group of each row, and only
     * if it is that row's number. A group is never that short, except the key's last, so no part of the
     * key is dropped, though the digits 1 to 9 are Base58 too.
     */
    private fun withoutRowNumbers(text: String): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val kept = ArrayList<String>(words.size)
        var groups = 0
        for ((i, word) in words.withIndex()) {
            val atRowStart = groups % ROW_SIZE == 0 && i < words.size - 1
            if (atRowStart && word.length < GROUP_SIZE && word == (groups + 1).toString())
                continue
            kept.add(word)
            groups++
        }
        return kept
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
        /** The number the user sees for a group ("Group 7"), as the rows of the key count them. */
        fun number(group: Int): Int = group + 1
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
