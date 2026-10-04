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

package io.bosonnetwork.identity.core.boson

import io.bosonnetwork.director.client.exceptions.DirectorException
import io.bosonnetwork.identity.core.model.AppError
import java.io.IOException
import java.util.concurrent.CompletionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorErrorsTest {
    private fun answered(status: Int, retryAfter: String? = null) =
        DirectorException.fromResponse(status, "refused", retryAfter)

    @Test
    fun `each status maps to its error`() {
        assertTrue(answered(400).toAppError() is AppError.InvalidInput)
        assertTrue(answered(401).toAppError() is AppError.Unauthorized)
        assertTrue(answered(403).toAppError() is AppError.Forbidden)
        assertTrue(answered(404).toAppError() is AppError.NotFound)
        assertTrue(answered(408).toAppError() is AppError.Timeout)
        assertTrue(answered(409).toAppError() is AppError.Conflict)
        assertTrue(answered(412).toAppError() is AppError.Conflict)
        assertTrue(answered(428).toAppError() is AppError.PassphraseRequired)
        assertTrue(answered(500).toAppError() is AppError.Unknown)
    }

    @Test
    fun `a 429 says how long to wait`() {
        assertEquals(60L, (answered(429, "60").toAppError() as AppError.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `no answer is a network error, wrapped or not`() {
        assertTrue(DirectorException("no answer", IOException("refused")).toAppError() is AppError.Network)
        assertTrue(CompletionException(IOException("reset")).toAppError() is AppError.Network)
    }

    @Test
    fun `an AppError passes through`() {
        val e = AppError.Conflict("x")
        assertSame(e, e.toAppError())
    }
}
