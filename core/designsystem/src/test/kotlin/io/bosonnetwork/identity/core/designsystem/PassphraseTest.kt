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

import io.bosonnetwork.identity.core.designsystem.component.PassphraseProblem
import io.bosonnetwork.identity.core.designsystem.component.passphraseProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The strength rule for a new passphrase: the Director's own, so the app refuses what the node would. */
class PassphraseTest {
    @Test
    fun `a passphrase needs 8 characters, 5 of them different`() {
        assertEquals(PassphraseProblem.TOO_SHORT, passphraseProblem(""))
        assertEquals(PassphraseProblem.TOO_SHORT, passphraseProblem("abcdefg"))
        assertEquals(PassphraseProblem.TOO_PLAIN, passphraseProblem("aaaabbbb"))
        assertEquals(PassphraseProblem.TOO_PLAIN, passphraseProblem("abcdabcd"))
        assertNull(passphraseProblem("abcdeabc"))
        assertNull(passphraseProblem("correct horse"))
    }

    @Test
    fun `characters are counted as code points`() {
        // Seven emoji are 14 UTF-16 units but only 7 characters.
        assertEquals(PassphraseProblem.TOO_SHORT, passphraseProblem("\uD83D\uDE00".repeat(7)))
        assertNull(passphraseProblem("\u5BC6\u7801\u4FDD\u62A4\u6211\u7684\u8D26\u6237"))
    }
}
