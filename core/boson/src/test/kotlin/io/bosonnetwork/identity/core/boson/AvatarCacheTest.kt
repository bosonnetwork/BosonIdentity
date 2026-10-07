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

import io.bosonnetwork.Id
import io.bosonnetwork.director.client.Avatar
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarCacheTest {
    private val dir = Files.createTempDirectory("avatars").toFile()
    private val userId = Id.random().toString()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `an avatar is kept with its validators, and found again by a new instance`() = runBlocking {
        AvatarCache(dir).put(userId, Avatar.of("image/png", byteArrayOf(1, 2), "\"v1\"", "Tue, 06 Oct 2026 10:00:00 GMT"))

        val again = AvatarCache(dir)
        again.load()
        assertArrayEquals(byteArrayOf(1, 2), again.avatars.value[userId])
        val held = again.held(userId)!!
        assertEquals("image/png", held.contentType)
        assertEquals("\"v1\"", held.eTag.orElse(null))
        assertEquals("Tue, 06 Oct 2026 10:00:00 GMT", held.lastModified.orElse(null))
        assertTrue(held.hasValidators())
    }

    @Test
    fun `forgetting an avatar removes it, on disk too`() = runBlocking {
        val cache = AvatarCache(dir)
        cache.put(userId, Avatar.of("image/png", byteArrayOf(1), null, null))
        cache.put(userId, null)
        assertNull(cache.avatars.value[userId])
        assertNull(AvatarCache(dir).held(userId))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `only user ids name files`() {
        runBlocking { AvatarCache(dir).put("../escape", Avatar.of("image/png", byteArrayOf(1), null, null)) }
    }
}
