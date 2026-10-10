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
import io.bosonnetwork.identity.core.model.LocalProfile
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.model.ProfileFields
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The move code: one QR code, opened with a code typed on the new phone. */
class MoveCodeTest {

    private val key = Signature.KeyPair.random().privateKey().bytes()
    private val node = NodeAddress("https://node.example:8443", "DJCLv7krQrqqjKvwoiSZnJrb7UZHEiFz7oTbQDfDaMUM")
    private val profile = LocalProfile(ProfileFields(name = "Alice", bio = "Hello from the old phone", email = "alice@example.com", avatar = "ab"), 7)

    @Test
    fun `the new phone opens what the old one sealed, with the code`() {
        val code = MoveCode.newCode()
        val text = MoveCode.seal(MovePayload(key, node, true, profile), code)
        assertTrue(text.startsWith("boson:move:1:"))
        assertTrue(MoveCode.isMoveCode(text))

        val opened = MoveCode.open(text, code)
        assertArrayEquals(key, opened.privateKey)
        assertEquals(node, opened.homeNode)
        assertTrue(opened.backupVerified)
        // The profile's text moves; the avatar doesn't, and the revision is synced again on the new phone.
        assertEquals(ProfileFields(name = "Alice", bio = "Hello from the old phone", email = "alice@example.com"), opened.profile.fields)
        assertNull(opened.profile.revision)
    }

    @Test
    fun `an identity without a node moves too`() {
        val code = MoveCode.newCode()
        val opened = MoveCode.open(MoveCode.seal(MovePayload(key, null, false, LocalProfile()), code), code)
        assertNull(opened.homeNode)
        assertFalse(opened.backupVerified)
    }

    @Test
    fun `another code opens nothing`() {
        val text = MoveCode.seal(MovePayload(key, node, true, profile), MoveCode.newCode())
        assertThrows(MoveCode.WrongCode::class.java) { MoveCode.open(text, MoveCode.newCode()) }
        assertThrows(MoveCode.WrongCode::class.java) { MoveCode.open(text, "too short") }
    }

    @Test
    fun `the code is generated, ten characters people read and type easily`() {
        val codes = (1..50).map { MoveCode.newCode() }
        assertTrue(codes.all { Regex("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{2}").matches(it) })
        assertEquals(50, codes.toSet().size)
    }

    @Test
    fun `a typed code ignores case, spaces and dashes, and reads O, I and L as digits`() {
        assertEquals("7KQ4M9XD2F", MoveCode.normalize(" 7kq4-m9xd 2f "))
        assertEquals("0110000000", MoveCode.normalize("OIL0000000"))
        assertNull(MoveCode.normalize("7KQ4M9XD2"))
        assertNull(MoveCode.normalize("7KQ4M9XD2U"))
    }

    @Test
    fun `other text is not a move code`() {
        assertFalse(MoveCode.isMoveCode("boson:pair:1:abc:def"))
        assertThrows(MoveCode.NotAMoveCode::class.java) { MoveCode.open("boson:supernode:1:x:https://n", "7KQ4M9XD2F") }
        assertThrows(MoveCode.NotAMoveCode::class.java) { MoveCode.open("boson:move:1:!!:??", "7KQ4M9XD2F") }
    }

    @Test
    fun `the code fits a QR code a phone scans from another`() {
        val long = LocalProfile(ProfileFields(name = "A".repeat(64), bio = "B".repeat(280), email = "c".repeat(60) + "@example.com"))
        val text = MoveCode.seal(MovePayload(key, node, true, long), MoveCode.newCode())
        assertTrue("${text.length} characters", text.length < 1400)
    }
}
