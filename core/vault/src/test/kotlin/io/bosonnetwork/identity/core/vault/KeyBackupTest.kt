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
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBackupTest {
    private val keyPair = Signature.KeyPair.random()
    private val private64 = keyPair.privateKey().bytes()
    private val base58 = KeyBackup.encode(private64)
    private val userId = Id.of(keyPair.publicKey().bytes()).toString()

    @Test
    fun `the key file reads back, in the portal's own format`() {
        val text = KeyBackup.keyFileText(base58, userId)
        assertTrue(text.startsWith("Boson User Identity Key\n\nprivateKey: $base58\npublicKey:  $userId\n"))
        assertArrayEquals(private64, KeyBackup.parse(text))
    }

    @Test
    fun `a bare key reads in Base58 and in hex`() {
        assertArrayEquals(private64, KeyBackup.parse("  $base58\n"))
        val hex = "0x" + private64.joinToString("") { "%02x".format(it) }
        assertArrayEquals(private64, KeyBackup.parse(hex))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a key whose halves don't belong together is refused`() {
        val other = Signature.KeyPair.random().publicKey().bytes()
        KeyBackup.parse(KeyBackup.encode(private64.copyOfRange(0, 32) + other))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `text that is no key is refused`() {
        KeyBackup.parse("not a key at all")
    }

    @Test
    fun `groups are four characters and cover the whole key`() {
        val groups = KeyBackup.groups(base58)
        assertTrue(groups.dropLast(1).all { it.length == 4 })
        assertEquals(base58, groups.joinToString(""))
    }

    @Test
    fun `a challenge asks for two different full groups, in order`() {
        repeat(200) {
            val c = KeyBackup.challenge(base58, Random(it))
            assertTrue(c.first < c.second)
            assertTrue(c.second < base58.length / 4)
        }
    }

    @Test
    fun `the right groups verify, spaces ignored, case kept`() {
        val c = KeyBackup.Challenge(2, 9)
        val groups = KeyBackup.groups(base58)
        assertTrue(KeyBackup.verify(base58, c, " ${groups[2]} ", groups[9].chunked(2).joinToString(" ")))
        assertFalse(KeyBackup.verify(base58, c, groups[9], groups[2]))
        val swapped = groups[2].map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString("")
        if (swapped != groups[2]) assertFalse(KeyBackup.verify(base58, c, swapped, groups[9]))
    }

    @Test
    fun `ranges are 1-based and inclusive`() {
        val c = KeyBackup.Challenge(3, 15)
        assertEquals(13..16, c.range(3, base58))
        assertNotEquals(c.range(3, base58), c.range(15, base58))
    }
}
