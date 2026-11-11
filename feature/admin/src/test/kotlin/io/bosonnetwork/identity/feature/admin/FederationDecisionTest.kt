/*
 * Copyright (c) 2026 -      bosonnetwork.io
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

package io.bosonnetwork.identity.feature.admin

import io.bosonnetwork.Id
import io.bosonnetwork.director.client.FederatedNode
import io.bosonnetwork.identity.core.boson.AdminState
import io.bosonnetwork.service.Admission
import io.mockk.every
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FederationDecisionTest {
    private val fake = FakeAdmin()
    private val admin = fake.admin
    private val target = Id.random()

    @Before
    fun setUp() {
        fake.setUp()
        every { admin.listFederationProposals(null, null) } returns done(page(emptyList()))
    }

    @After
    fun tearDown() = fake.tearDown()

    private fun ready(): FederationViewModel {
        val vm = fake.track(FederationViewModel(fake.session, fake.context))
        fake.open()
        return vm
    }

    @Test
    fun `admission validates the complete ID and note before submitting a trimmed note`() = runTest {
        every { admin.admitFederatedNode(target, "partner") } returns done(federatedNode(target))
        val vm = ready()
        vm.startDecision(FederationDecision.ADMIT)
        vm.onDecisionIdChange("not an ID")
        vm.saveDecision()
        vm.onDecisionIdChange(" $target ")
        vm.onDecisionNoteChange("x".repeat(257))
        vm.saveDecision()
        verify(exactly = 0) { admin.admitFederatedNode(any(), any()) }
        vm.onDecisionNoteChange(" partner ")
        vm.saveDecision()
        assertNull(vm.state.value.decision)
        assertEquals(1, vm.state.value.nodeChanges)
        verify(exactly = 1) { admin.admitFederatedNode(target, "partner") }
        verify(exactly = 0) { admin.listFederationProposals(null, null) }
    }

    @Test
    fun `suspension requires a second step and a case-sensitive code`() = runTest {
        val id = generateSequence { Id.random() }.first { it.toString().take(4).any(Char::isLetter) }
        every { admin.suspendFederatedNode(id, null) } returns done(federatedNode(id, state = FederatedNode.State.SUSPENDED))
        val vm = ready()
        vm.startDecision(FederationDecision.SUSPEND)
        vm.onDecisionIdChange(id.toString())
        vm.saveDecision()
        val form = vm.state.value.decision!!
        assertTrue(form.confirming)
        verify(exactly = 0) { admin.suspendFederatedNode(any(), any()) }
        val wrongCase = form.code.map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString("")
        vm.saveDecision(wrongCase)
        verify(exactly = 0) { admin.suspendFederatedNode(any(), any()) }
        vm.saveDecision(form.code)
        verify(exactly = 1) { admin.suspendFederatedNode(id, null) }
        assertEquals(1, vm.state.value.nodeChanges)
    }

    @Test
    fun `row decisions retain their target and existing note`() = runTest {
        every { admin.suspendFederatedNode(target, "existing note") } returns done(federatedNode(target, state = FederatedNode.State.SUSPENDED))
        val vm = ready()
        vm.startDecision(FederationDecision.SUSPEND, nodeView(target, note = "existing note"))
        vm.onDecisionIdChange(Id.random().toString())
        assertEquals(target.toString(), vm.state.value.decision!!.nodeId)
        assertEquals("existing note", vm.state.value.decision!!.note)
        assertTrue(vm.state.value.decision!!.existing)
        vm.saveDecision()
        vm.saveDecision(confirmCode(target.toString()))
        verify { admin.suspendFederatedNode(target, "existing note") }
    }

    @Test
    fun `editing the target resets a pending typed confirmation`() = runTest {
        val vm = ready()
        vm.startDecision(FederationDecision.SUSPEND)
        vm.onDecisionIdChange(target.toString())
        vm.saveDecision()
        assertTrue(vm.state.value.decision!!.confirming)
        vm.onDecisionIdChange(Id.random().toString())
        assertFalse(vm.state.value.decision!!.confirming)
        vm.cancel()
        assertNull(vm.state.value.decision)
        verify(exactly = 0) { admin.suspendFederatedNode(any(), any()) }
    }

    @Test
    fun `duplicate submits and edits while busy do not change the request`() = runTest {
        val pending = CompletableFuture<FederatedNode>()
        every { admin.admitFederatedNode(target, null) } returns pending
        val vm = ready()
        vm.startDecision(FederationDecision.ADMIT)
        vm.onDecisionIdChange(target.toString())
        vm.saveDecision()
        vm.saveDecision()
        vm.onDecisionIdChange(Id.random().toString())
        vm.onDecisionNoteChange("changed")
        vm.startDecision(FederationDecision.SUSPEND)
        assertTrue(vm.state.value.busy)
        assertEquals(target.toString(), vm.state.value.decision!!.nodeId)
        assertEquals("", vm.state.value.decision!!.note)
        verify(exactly = 1) { admin.admitFederatedNode(target, null) }
        pending.complete(federatedNode(target))
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `failed decisions retain the form for retry without invalidating lists`() = runTest {
        every { admin.admitFederatedNode(target, null) } returns refused(500)
        val vm = ready()
        vm.startDecision(FederationDecision.ADMIT)
        vm.onDecisionIdChange(target.toString())
        vm.saveDecision()
        assertNotNull(vm.state.value.decision)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertEquals(0, vm.state.value.nodeChanges)
        every { admin.admitFederatedNode(target, null) } returns done(federatedNode(target))
        vm.saveDecision()
        assertEquals(1, vm.state.value.nodeChanges)
    }

    @Test
    fun `hidden proposal history is invalidated and only reloaded on selection`() = runTest {
        every { admin.admitFederatedNode(target, null) } returns done(federatedNode(target))
        val vm = ready()
        vm.selectView(FederationView.PROPOSALS)
        vm.content.first { it is Loaded.Ready }
        vm.selectView(FederationView.NODES)
        vm.startDecision(FederationDecision.ADMIT)
        vm.onDecisionIdChange(target.toString())
        vm.saveDecision()
        verify(exactly = 1) { admin.listFederationProposals(null, null) }
        vm.selectView(FederationView.PROPOSALS)
        vm.content.first { it is Loaded.Ready }
        verify(exactly = 2) { admin.listFederationProposals(null, null) }
    }

    @Test
    fun `post-decision refresh uses the latest node search and authoritative admission`() = runTest {
        val next = Id.random()
        val pending = CompletableFuture<FederatedNode>()
        every { admin.admitFederatedNode(target, null) } returns pending
        every { admin.listFederatedNodes(null, 1, FEDERATION_PAGE) } returns done(page(listOf(federatedNode(target)), pageSize = FEDERATION_PAGE))
        every { admin.listFederatedNodes(next, 1, FEDERATION_PAGE) } returns done(page(listOf(federatedNode(next,
            state = FederatedNode.State.ACTIVE, admission = Admission.Reason.BLOCKED)), pageSize = FEDERATION_PAGE))
        val actions = ready()
        val nodes = fake.track(FederationNodesViewModel(fake.session, fake.context))
        nodes.content.first { it is Loaded.Ready }
        actions.startDecision(FederationDecision.ADMIT)
        actions.onDecisionIdChange(target.toString())
        actions.saveDecision()
        nodes.onQueryChange(next.toString())
        pending.complete(federatedNode(target))
        assertEquals(1, actions.state.value.nodeChanges)
        // The visible view's RefreshOn handles this revision using its own current state.
        nodes.refreshNodes()
        val result = (nodes.content.value as Loaded.Ready).value.nodes.single()
        assertEquals(next.toString(), result.id)
        assertEquals(FederatedNode.State.ACTIVE, result.state)
        assertEquals(Admission.Reason.BLOCKED, result.admission)
        verify(exactly = 1) { admin.listFederatedNodes(next, 1, FEDERATION_PAGE) }
    }

    @Test
    fun `session end and replacement discard late decision success`() = runTest {
        val pending = CompletableFuture<FederatedNode>()
        every { admin.admitFederatedNode(target, null) } returns pending
        val vm = ready()
        vm.startDecision(FederationDecision.ADMIT)
        vm.onDecisionIdChange(target.toString())
        vm.saveDecision()
        fake.state.value = AdminState(fake.adminId, fake.node, Long.MAX_VALUE - 1)
        pending.complete(federatedNode(target))
        assertEquals(FederationUiState(), vm.state.value)
        vm.startDecision(FederationDecision.SUSPEND)
        fake.state.value = null
        vm.saveDecision()
        assertEquals(FederationUiState(), vm.state.value)
        verify(exactly = 0) { admin.suspendFederatedNode(any(), any()) }
    }

    @Test
    fun `a disabled API closes the pending form and invalidates displayed lists`() = runTest {
        every { admin.admitFederatedNode(target, null) } returns refused(501)
        val vm = ready()
        vm.startDecision(FederationDecision.ADMIT)
        vm.onDecisionIdChange(target.toString())
        vm.saveDecision()
        assertNull(vm.state.value.decision)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
        assertEquals(1, vm.state.value.nodeChanges)
    }

    @Test
    fun `disabled cached proposal history does not override a newly enabled Nodes view`() = runTest {
        every { admin.listFederationProposals(null, null) } returns refused(501)
        val vm = ready()
        vm.selectView(FederationView.PROPOSALS)
        vm.content.first { it is Loaded.Ready }
        vm.startPropose()
        assertNull(vm.state.value.propose)
        vm.selectView(FederationView.NODES)
        // Nodes exposes this action only after its own enabled read succeeds.
        vm.startPropose()
        assertNotNull(vm.state.value.propose)
    }

    @Test
    fun `no decision starts without an admin session`() = runTest {
        val vm = fake.track(FederationViewModel(fake.session, fake.context))
        vm.startDecision(FederationDecision.ADMIT)
        vm.onDecisionIdChange(target.toString())
        vm.saveDecision()
        assertNull(vm.state.value.decision)
        verify(exactly = 0) { admin.admitFederatedNode(any(), any()) }
    }
}
