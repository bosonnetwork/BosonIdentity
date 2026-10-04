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

package io.bosonnetwork.identity.core.designsystem

import io.bosonnetwork.identity.core.designsystem.component.IdenticonPattern
import io.bosonnetwork.identity.core.designsystem.component.groupedId
import io.bosonnetwork.identity.core.designsystem.component.shortId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdsTest {
    private val id = "7Hq3wZcLmN4pQrStUvWxYz2aBcDeFgHiJkLmNoPxK2p"

    @Test
    fun `short ids keep both ends`() {
        assertEquals("7Hq3...xK2p", shortId(id))
        assertEquals("short", shortId("short"))
    }

    @Test
    fun `grouped ids read in fours`() {
        assertEquals("7Hq3 wZcL mN4p", groupedId(id).take(14))
    }

    @Test
    fun `an identicon is the same every time, mirrored, and differs between ids`() {
        val a = IdenticonPattern.of(id)
        val again = IdenticonPattern.of(id)
        assertTrue(a.cells.contentEquals(again.cells))
        assertEquals(a.foreground, again.foreground)
        for (row in 0 until 5) {
            assertEquals(a.cells[row * 5], a.cells[row * 5 + 4])
            assertEquals(a.cells[row * 5 + 1], a.cells[row * 5 + 3])
        }
        val b = IdenticonPattern.of(id + "x")
        assertFalse(a.cells.contentEquals(b.cells) && a.foreground == b.foreground)
    }
}
