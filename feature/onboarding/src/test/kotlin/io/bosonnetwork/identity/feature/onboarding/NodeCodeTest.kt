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

package io.bosonnetwork.identity.feature.onboarding

import io.bosonnetwork.Id
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A node's QR code, in the form the portal and Boson Identity show now, and the JSON they showed before. */
class NodeCodeTest {
    private val nodeId = Id.random().toString()

    @Test
    fun `the super node code is read`() {
        assertEquals(
            ScannedNode("https://node.example:8443", nodeId),
            scannedNode("boson:supernode:1:$nodeId:https://node.example:8443/"),
        )
    }

    @Test
    fun `the JSON code from before is still read, with or without its id`() {
        assertEquals(ScannedNode("https://node.example", nodeId), scannedNode("""{"url":"https://node.example","id":"$nodeId"}"""))
        assertEquals(ScannedNode("http://10.0.2.2:8080", ""), scannedNode("""{"url":"http://10.0.2.2:8080"}"""))
    }

    @Test
    fun `anything else is not a node code`() {
        assertNull(scannedNode("https://node.example"))
        assertNull(scannedNode("boson:pair:1:abc:def"))
        assertNull(scannedNode("""{"id":"$nodeId"}"""))
        assertNull(scannedNode("boson:supernode:1::https://node.example"))
    }
}
