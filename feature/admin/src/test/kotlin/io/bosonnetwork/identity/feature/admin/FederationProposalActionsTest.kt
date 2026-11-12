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
import io.bosonnetwork.director.client.FederationProposal
import io.bosonnetwork.director.client.FederationProposal.Role
import io.bosonnetwork.director.client.FederationProposal.Status
import io.bosonnetwork.identity.core.boson.AdminState
import io.mockk.every
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FederationProposalActionsTest {
    private val fake = FakeAdmin()
    private val admin = fake.admin
    private val target = Id.random()
    private fun view(role: Role? = Role.ANSWER, status: Status? = Status.PENDING) =
        ProposalView(7, target.toString(), role, status, "note", 500, 1000, 0)

    @Before
    fun setUp() {
        fake.setUp()
        every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns done(page(listOf(proposal(7, target, role = Role.ANSWER))))
        every { admin.listFederationProposals(null, Role.ANSWER, Status.PENDING, 1, 1) } returns done(page(emptyList<FederationProposal>(), pageSize = 1, total = 3))
        every { fake.context.getString(R.string.adm_fed_proposal_changed) } returns "changed"
        every { fake.context.getString(R.string.adm_fed_proposed_sent) } returns "pending"
    }

    @After
    fun tearDown() = fake.tearDown()

    private suspend fun ready(): FederationViewModel {
        val vm = fake.track(FederationViewModel(fake.session, fake.context))
        fake.open()
        vm.selectView(FederationView.PROPOSALS)
        vm.content.first { it is Loaded.Ready }
        return vm
    }

    @Test
    fun `all role and status combinations select only valid actions`() = runTest {
        val vm = ready()
        for (role in listOf(null) + Role.entries) for (status in listOf(null) + Status.entries) {
            val row = view(role, status)
            assertEquals(role == Role.ANSWER && status == Status.PENDING, row.canAnswer)
            assertEquals(role != null && status != null && (status != Status.PENDING || role == Role.OFFER), row.canRemove)
            vm.ask(FederationChange.AnswerProposal(row, true))
            assertEquals(row.canAnswer, vm.state.value.change != null)
            vm.cancel()
            vm.ask(FederationChange.RemoveProposal(row))
            assertEquals(row.canRemove, vm.state.value.change != null)
            vm.cancel()
        }
        verify(exactly = 0) { admin.acceptFederationProposal(any()) }
        verify(exactly = 0) { admin.declineFederationProposal(any()) }
        verify(exactly = 0) { admin.removeFederationProposal(any()) }
    }

    @Test
    fun `confirming acceptance and decline invokes the matching endpoint and refreshes the global count`() = runTest {
        every { admin.acceptFederationProposal(7) } returns done(proposal(7, target, role = Role.ANSWER, status = Status.ACCEPTED))
        every { admin.declineFederationProposal(7) } returns done(proposal(7, target, role = Role.ANSWER, status = Status.DECLINED))
        val vm = ready()
        vm.ask(FederationChange.AnswerProposal(view(), true))
        verify(exactly = 0) { admin.acceptFederationProposal(any()) }
        vm.confirm()
        assertNull(vm.state.value.change)
        vm.ask(FederationChange.AnswerProposal(view(), false))
        vm.confirm()
        verify(exactly = 1) { admin.acceptFederationProposal(7) }
        verify(exactly = 1) { admin.declineFederationProposal(7) }
        verify(exactly = 3) { admin.listFederationProposals(null, Role.ANSWER, Status.PENDING, 1, 1) }
        assertEquals(2, vm.state.value.nodeChanges)
    }

    @Test
    fun `duplicate confirmation and replacement actions are ignored while busy`() = runTest {
        val pending = CompletableFuture<FederationProposal>()
        every { admin.acceptFederationProposal(7) } returns pending
        val vm = ready()
        val change = FederationChange.AnswerProposal(view(), true)
        vm.ask(change)
        vm.confirm()
        vm.confirm()
        vm.ask(FederationChange.AnswerProposal(view(), false))
        vm.cancel()
        assertEquals(change, vm.state.value.change)
        verify(exactly = 1) { admin.acceptFederationProposal(7) }
        verify(exactly = 0) { admin.declineFederationProposal(any()) }
        pending.complete(proposal(7, status = Status.ACCEPTED))
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `network failure retains the confirmation for explicit retry`() = runTest {
        every { admin.acceptFederationProposal(7) } returns refused(500)
        val vm = ready()
        vm.ask(FederationChange.AnswerProposal(view(), true))
        vm.confirm()
        assertNotNull(vm.state.value.change)
        assertNotNull(vm.state.value.error)
        assertEquals(0, vm.state.value.nodeChanges)
        every { admin.acceptFederationProposal(7) } returns done(proposal(7, status = Status.ACCEPTED))
        vm.confirm()
        assertNull(vm.state.value.change)
        assertEquals(1, vm.state.value.nodeChanges)
    }

    @Test
    fun `conflict and missing proposal close stale confirmations and reload authoritative rows`() = runTest {
        val vm = ready()
        for (code in listOf(409, 404)) {
            every { admin.acceptFederationProposal(7) } returns refused(code)
            every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns done(page(emptyList()))
            vm.ask(FederationChange.AnswerProposal(view(), true))
            vm.confirm()
            assertNull(vm.state.value.change)
            assertEquals("changed", vm.state.value.notice)
            assertTrue((vm.content.value as Loaded.Ready).value.proposals.isEmpty())
            vm.noticeSeen()
            assertNull(vm.state.value.notice)
        }
        assertEquals(2, vm.state.value.nodeChanges)
    }

    @Test
    fun `withdraw refreshes the record as withdrawn rather than deleting it optimistically`() = runTest {
        every { admin.removeFederationProposal(7) } answers {
            every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns
                done(page(listOf(proposal(7, target, status = Status.WITHDRAWN))))
            ok()
        }
        val vm = ready()
        vm.ask(FederationChange.RemoveProposal(view(Role.OFFER)))
        vm.confirm()
        assertEquals(Status.WITHDRAWN, (vm.content.value as Loaded.Ready).value.proposals.single().status)
    }

    @Test
    fun `removing the final filtered record keeps filters and returns an empty current page`() = runTest {
        every { admin.listFederationProposals(null, Role.ANSWER, Status.EXPIRED, 1, FEDERATION_PAGE) } returns
            done(page(listOf(proposal(7, target, role = Role.ANSWER, status = Status.EXPIRED))))
        every { admin.removeFederationProposal(7) } answers {
            every { admin.listFederationProposals(null, Role.ANSWER, Status.EXPIRED, 1, FEDERATION_PAGE) } returns done(page(emptyList()))
            ok()
        }
        val vm = ready()
        vm.setProposalFilters(Role.ANSWER, Status.EXPIRED)
        vm.ask(FederationChange.RemoveProposal(view(status = Status.EXPIRED)))
        vm.confirm()
        assertEquals(Role.ANSWER, vm.proposals.value.role)
        assertEquals(Status.EXPIRED, vm.proposals.value.status)
        assertEquals(0L, (vm.content.value as Loaded.Ready).value.total)
    }

    @Test
    fun `delayed answer refreshes the latest filters when a hidden view is opened`() = runTest {
        val pending = CompletableFuture<FederationProposal>()
        every { admin.acceptFederationProposal(7) } returns pending
        every { admin.listFederationProposals(null, null, Status.DECLINED, 1, FEDERATION_PAGE) } returns done(page(emptyList()))
        val vm = ready()
        vm.ask(FederationChange.AnswerProposal(view(), true))
        vm.confirm()
        vm.setProposalFilters(null, Status.DECLINED)
        vm.selectView(FederationView.NODES)
        pending.complete(proposal(7, status = Status.ACCEPTED))
        verify(exactly = 1) { admin.listFederationProposals(null, null, Status.DECLINED, 1, FEDERATION_PAGE) }
        vm.selectView(FederationView.PROPOSALS)
        verify(exactly = 2) { admin.listFederationProposals(null, null, Status.DECLINED, 1, FEDERATION_PAGE) }
        assertEquals(Status.DECLINED, vm.proposals.value.status)
    }

    @Test
    fun `session replacement and unauthorized completion cannot restore a pending confirmation`() = runTest {
        val pending = object : CompletableFuture<FederationProposal>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        every { admin.acceptFederationProposal(7) } returns pending
        val vm = ready()
        vm.ask(FederationChange.AnswerProposal(view(), true))
        vm.confirm()
        fake.state.value = AdminState(fake.adminId, fake.node, Long.MAX_VALUE - 1)
        pending.complete(proposal(7, status = Status.ACCEPTED))
        assertEquals(FederationUiState(), vm.state.value)
        every { admin.acceptFederationProposal(7) } answers {
            fake.state.value = null
            refused(401)
        }
        vm.ask(FederationChange.AnswerProposal(view(), true))
        vm.confirm()
        assertEquals(FederationUiState(), vm.state.value)
        assertTrue(vm.content.value is Loaded.Idle)
    }

    @Test
    fun `proposal note validation trims accepted notes and prevents edits during submission`() = runTest {
        val pending = CompletableFuture<FederationProposal>()
        every { admin.proposeFederation(target, "operator note") } returns pending
        val vm = ready()
        vm.startPropose()
        vm.onProposeIdChange(target.toString())
        vm.onProposeNoteChange("n".repeat(257))
        vm.propose()
        verify(exactly = 0) { admin.proposeFederation(any(), any()) }
        vm.onProposeNoteChange(" operator note ")
        vm.propose()
        vm.onProposeNoteChange("changed")
        vm.onProposeIdChange(Id.random().toString())
        assertEquals(" operator note ", vm.state.value.propose!!.note)
        assertEquals(target.toString(), vm.state.value.propose!!.nodeId)
        pending.complete(proposal(7))
        assertEquals("pending", vm.state.value.proposed)
        verify(exactly = 1) { admin.proposeFederation(target, "operator note") }
    }
    @Test
    fun `a completed answer uses the latest draft query before its debounce ends`() = runTest {
        val pending = CompletableFuture<FederationProposal>()
        every { admin.acceptFederationProposal(7) } returns pending
        val next = Id.random()
        every { admin.listFederationProposals(next, null, null, 1, FEDERATION_PAGE) } returns done(page(emptyList()))
        val vm = ready()
        vm.ask(FederationChange.AnswerProposal(view(), true))
        vm.confirm()
        vm.onProposalQueryChange(next.toString())
        pending.complete(proposal(7, status = Status.ACCEPTED))
        assertEquals(next.toString(), vm.proposals.value.appliedQuery)
        assertTrue((vm.content.value as Loaded.Ready).value.proposals.isEmpty())
        advanceTimeBy(250)
        runCurrent()
        verify(exactly = 1) { admin.listFederationProposals(next, null, null, 1, FEDERATION_PAGE) }
    }

    @Test
    fun `the longest note and blank notes are submitted using the supported wire forms`() = runTest {
        val note = "n".repeat(256)
        every { admin.proposeFederation(target, note) } returns done(proposal(7))
        every { admin.proposeFederation(target, null) } returns done(proposal(8))
        val vm = ready()
        for (value in listOf(note, "   ")) {
            vm.startPropose()
            vm.onProposeIdChange(target.toString())
            vm.onProposeNoteChange(value)
            vm.propose()
            assertNull(vm.state.value.propose)
            vm.proposedSeen()
        }
        verify(exactly = 1) { admin.proposeFederation(target, note) }
        verify(exactly = 1) { admin.proposeFederation(target, null) }
    }

}
