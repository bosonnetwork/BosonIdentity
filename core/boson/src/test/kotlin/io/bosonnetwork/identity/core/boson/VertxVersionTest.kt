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

import io.vertx.core.Vertx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vert.x's version is read on Android without `Scanner(InputStream, Charset)`, which phones before
 * Android 13 lack: the app fills in the cached value first.
 */
class VertxVersionTest {
    private val field = Class.forName("io.vertx.core.impl.VertxImpl").getDeclaredField("version").apply { isAccessible = true }

    @Test
    fun `the version is filled in from the jar's own file, before Vert-x reads it`() {
        field.set(null, null)
        NodeConnections.seedVertxVersion()

        val expected = Vertx::class.java.classLoader!!.getResourceAsStream("META-INF/vertx/vertx-version.txt")!!
            .use { it.readBytes().toString(Charsets.UTF_8).trim() }
        assertTrue(expected.isNotEmpty())
        assertEquals(expected, field.get(null))
    }

    @Test
    fun `a version already known is kept`() {
        field.set(null, "known")
        NodeConnections.seedVertxVersion()
        assertEquals("known", field.get(null))
        field.set(null, null)
    }
}
