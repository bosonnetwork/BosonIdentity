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

package io.bosonnetwork.identity

import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.model.NodeAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppRequestViewModelTest {
    private val node = NodeAddress("https://node.example", "node1")
    private val alice = IdentityInfo("alice", homeNode = node)
    private val bob = IdentityInfo("bob", homeNode = node)
    private val nodeless = IdentityInfo("carol")

    @Test
    fun `the identity proposed is the one asked for, else the one in use, with a home node`() {
        val identities = listOf(alice, bob, nodeless)
        assertEquals("bob", AppRequestViewModel.propose(identities, asked = "bob", active = "alice"))
        assertEquals("alice", AppRequestViewModel.propose(identities, asked = "unknown", active = "alice"))
        assertEquals("alice", AppRequestViewModel.propose(identities, asked = "carol", active = "carol"))
        assertNull(AppRequestViewModel.propose(listOf(nodeless), asked = null, active = "carol"))
    }

    @Test
    fun `an approval goes straight to the identity asked for, or the only one`() {
        assertEquals("bob", AppRequestViewModel.direct(listOf(alice, bob), asked = "bob"))
        assertEquals("alice", AppRequestViewModel.direct(listOf(alice), asked = null))
        assertEquals("alice", AppRequestViewModel.direct(listOf(alice), asked = "unknown"))
        // Several, none asked for: the user picks; one without a home node can't approve.
        assertNull(AppRequestViewModel.direct(listOf(alice, bob), asked = null))
        assertNull(AppRequestViewModel.direct(listOf(nodeless), asked = null))
    }

    @Test
    fun `a request can't be answered without an identity, or without a home node`() {
        assertEquals(AppRequests.ERROR_NO_IDENTITY, AppRequestState(loading = false).unanswerable)
        assertEquals(AppRequests.ERROR_NO_HOME_NODE, AppRequestState(loading = false, identities = listOf(nodeless)).unanswerable)
        assertNull(AppRequestState(loading = false, identities = listOf(nodeless, alice)).unanswerable)
        assertNull(AppRequestState().unanswerable)
    }
}
