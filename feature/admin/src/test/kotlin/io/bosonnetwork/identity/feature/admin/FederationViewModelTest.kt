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

package io.bosonnetwork.identity.feature.admin

import io.bosonnetwork.Id
import io.bosonnetwork.director.client.FederationProposal
import io.bosonnetwork.identity.core.boson.AdminState
import io.bosonnetwork.json.Json
import io.bosonnetwork.service.FederatedNodeDescriptor
import io.bosonnetwork.service.FederationMode
import io.mockk.every
import io.mockk.verify
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Current federation DTOs, proposal outcomes and descriptor/session ordering. */
class FederationViewModelTest {

    private val fake = FakeAdmin()
    private val admin = fake.admin
    private val otherId = Id.random()

    @Before
    fun setUp() {
        fake.setUp()
        every { admin.listFederationProposals(null, FederationProposal.Role.ANSWER,
            FederationProposal.Status.PENDING, 1, 1) } returns
            done(page(emptyList<FederationProposal>(), pageSize = 1))
        every { fake.context.getString(R.string.adm_fed_propose_blacklisted) } returns "blacklisted"
        every { fake.context.getString(R.string.adm_fed_proposed_sent) } returns "pending"
        every { fake.context.getString(R.string.adm_fed_proposed_accepted) } returns "accepted"
        every { fake.context.getString(R.string.adm_fed_proposed_crossed) } returns "crossed"
        every { fake.context.getString(R.string.adm_fed_proposed_declined) } returns "declined"
        every { fake.context.getString(R.string.adm_fed_proposed_other) } returns "recorded"
        every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns done(page(listOf(proposal(2, createdAt = 2_000), proposal(1))))
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

    private fun FederationViewModel.federation(): FederationContent = (content.value as Loaded.Ready).value

    @Test
    fun `proposal history loads only when selected and keeps the server order`() = runTest {
        val vm = fake.track(FederationViewModel(fake.session, fake.context))
        fake.open()
        assertEquals(FederationView.NODES, vm.state.value.view)
        verify(exactly = 0) { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) }
        vm.selectView(FederationView.PROPOSALS)
        vm.content.first { it is Loaded.Ready }
        assertTrue(vm.federation().enabled)
        assertEquals(listOf(2L, 1L), vm.federation().proposals.map { it.id })
        verify(exactly = 0) { admin.getFederatedNodeDescriptor(any()) }
    }

    @Test
    fun `unknown optional fields do not invent a state or allow proposal removal`() = runTest {
        val proposal = Json.parse("""{"id":3,"nodeId":"$otherId","role":"future","status":"future"}""", FederationProposal::class.java)
        every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns done(page(listOf(proposal)))
        val vm = ready()
        assertFalse(vm.federation().proposals.single().canRemove)
        vm.ask(FederationChange.RemoveProposal(vm.federation().proposals.single()))
        vm.confirm()
        verify(exactly = 0) { admin.removeFederationProposal(any()) }
    }

    @Test
    fun `disabled federation is distinct from a failed request`() = runTest {
        every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns refused(501)
        val vm = ready()
        assertFalse(vm.federation().enabled)
        vm.startPropose()
        assertNull(vm.state.value.propose)

        every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns refused(500)
        vm.refresh()
        vm.content.first { it is Loaded.Failed }
    }

    @Test
    fun `federation is proposed by full id and the pending result is reported`() = runTest {
        val target = Id.random()
        val pending = CompletableFuture<FederationProposal>()
        every { admin.proposeFederation(target, null) } returns pending
        val vm = ready()
        vm.startPropose()
        vm.onProposeIdChange("not an id")
        vm.propose()
        verify(exactly = 0) { admin.proposeFederation(any(), any()) }

        vm.onProposeIdChange(" $target ")
        vm.propose()
        vm.propose()
        assertTrue(vm.state.value.busy)
        pending.complete(proposal(3, target))
        assertEquals("pending", vm.state.first { it.proposed != null }.proposed)
        assertNull(vm.state.value.propose)
        verify(exactly = 1) { admin.proposeFederation(target, null) }
        verify(exactly = 2) { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) }
    }

    @Test
    fun `accepted, declined and crossed proposals report their actual outcome`() = runTest {
        val vm = ready()
        val target = Id.random()
        for ((result, message) in listOf(
            proposal(3, target, status = FederationProposal.Status.ACCEPTED) to "accepted",
            proposal(4, target, status = FederationProposal.Status.DECLINED) to "declined",
            proposal(5, target, role = FederationProposal.Role.ANSWER, status = FederationProposal.Status.ACCEPTED) to "crossed",
            proposal(6, target, status = null) to "recorded",
        )) {
            every { admin.proposeFederation(target, null) } returns done(result)
            vm.startPropose()
            vm.onProposeIdChange(target.toString())
            vm.propose()
            assertEquals(message, vm.state.first { it.proposed != null }.proposed)
        }
    }

    @Test
    fun `a blacklisted node cannot be proposed to`() = runTest {
        val target = Id.random()
        every { admin.proposeFederation(target, null) } returns refused(403)
        val vm = ready()
        vm.startPropose()
        vm.onProposeIdChange(target.toString())
        vm.propose()
        assertEquals("blacklisted", vm.state.first { it.error != null }.error)
    }

    @Test
    fun `removing a relationship requires its confirmation code and refreshes affected lists`() = runTest {
        every { admin.removeFederatedNode(otherId) } answers {
            ok()
        }
        val vm = ready()
        vm.ask(FederationChange.RemoveNode(nodeView(otherId)))
        vm.confirm("x")
        verify(exactly = 0) { admin.removeFederatedNode(any()) }
        vm.confirm(otherId.toString().take(4))
        vm.state.first { it.change == null }
        assertEquals(1, vm.state.value.nodeChanges)
        verify(exactly = 2) { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) }
    }

    @Test
    fun `pending incoming proposals cannot be removed and finished records can`() = runTest {
        val incoming = proposal(3, role = FederationProposal.Role.ANSWER)
        val finished = proposal(4, role = FederationProposal.Role.ANSWER, status = FederationProposal.Status.EXPIRED,
            note = "finished", answeredAt = 2_000)
        every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns done(page(listOf(incoming, finished)))
        every { admin.removeFederationProposal(4) } answers {
            every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns done(page(listOf(incoming)))
            ok()
        }
        val vm = ready()
        assertFalse(vm.federation().proposals.first().canRemove)
        vm.ask(FederationChange.RemoveProposal(vm.federation().proposals.first()))
        vm.confirm()
        verify(exactly = 0) { admin.removeFederationProposal(any()) }
        val record = vm.federation().proposals.last()
        assertEquals("finished", record.note)
        assertEquals(2_000L, record.answeredAt)
        vm.ask(FederationChange.RemoveProposal(record))
        vm.confirm()
        vm.state.first { it.change == null }
        assertEquals(listOf(3L), vm.federation().proposals.map { it.id })
    }

    @Test
    fun `a pending outgoing proposal can be withdrawn`() = runTest {
        every { admin.removeFederationProposal(2) } answers {
            every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns done(page(listOf(proposal(1))))
            ok()
        }
        val vm = ready()
        vm.ask(FederationChange.RemoveProposal(vm.federation().proposals.first()))
        vm.confirm()
        vm.state.first { it.change == null }
        assertEquals(listOf(1L), vm.federation().proposals.map { it.id })
    }

    @Test
    fun `descriptor detail uses the shared model and retries an unavailable description`() = runTest {
        every { admin.getFederatedNodeDescriptor(otherId) } returns done(Optional.empty())
        val vm = ready()
        vm.openDescriptor(otherId.toString())
        assertNull((vm.state.first { it.descriptor?.second is Loaded.Ready }.descriptor!!.second as Loaded.Ready).value)
        every { admin.getFederatedNodeDescriptor(otherId) } returns done(Optional.of(descriptor(otherId)))
        vm.openDescriptor(otherId.toString())
        val value = (vm.state.first { (it.descriptor?.second as? Loaded.Ready)?.value != null }.descriptor!!.second as Loaded.Ready).value!!
        assertEquals(FederationMode.MUTUAL, value.mode.orElse(null))
        assertEquals("Ion Store", value.services.single().name.orElse(null))
        assertTrue(value.services.single().isFederation)
        vm.closeDescriptor()
        assertNull(vm.state.value.descriptor)
    }

    @Test
    fun `descriptor errors can be retried and closing discards a late result`() = runTest {
        every { admin.getFederatedNodeDescriptor(otherId) } returns refused(500)
        val vm = ready()
        vm.openDescriptor(otherId.toString())
        vm.state.first { it.descriptor?.second is Loaded.Failed }
        val pending = CompletableFuture<Optional<FederatedNodeDescriptor>>()
        every { admin.getFederatedNodeDescriptor(otherId) } returns pending
        vm.openDescriptor(otherId.toString())
        vm.closeDescriptor()
        pending.complete(Optional.of(descriptor(otherId)))
        assertNull(vm.state.value.descriptor)
    }

    @Test
    fun `a replaced descriptor selection cannot be overwritten by the old response`() = runTest {
        val next = Id.random()
        val old = CompletableFuture<Optional<FederatedNodeDescriptor>>()
        every { admin.getFederatedNodeDescriptor(otherId) } returns old
        every { admin.getFederatedNodeDescriptor(next) } returns done(Optional.of(descriptor(next)))
        val vm = ready()
        vm.openDescriptor(otherId.toString())
        vm.openDescriptor(next.toString())
        old.complete(Optional.of(descriptor(otherId)))
        val selected = vm.state.value.descriptor!!
        assertEquals(next.toString(), selected.first)
        assertEquals(next, (selected.second as Loaded.Ready).value!!.nodeId)
    }

    @Test
    fun `ending the session clears pending actions and a late success stays silent`() = runTest {
        val pending = CompletableFuture<FederationProposal>()
        every { admin.proposeFederation(otherId, null) } returns pending
        val vm = ready()
        val node = nodeView(otherId)
        vm.startPropose()
        vm.onProposeIdChange(otherId.toString())
        vm.propose()
        fake.state.value = null
        pending.complete(proposal(3, status = FederationProposal.Status.ACCEPTED))
        assertEquals(FederationUiState(), vm.state.value)
        assertTrue(vm.content.value is Loaded.Idle)
        vm.ask(FederationChange.RemoveNode(node))
        vm.openDescriptor(otherId.toString())
        vm.startPropose()
        assertEquals(FederationUiState(), vm.state.value)
        verify(exactly = 0) { admin.getFederatedNodeDescriptor(any()) }
    }

    @Test
    fun `replacing a session without a null emission clears dialogs and reloads its content`() = runTest {
        val vm = ready()
        vm.startPropose()
        fake.state.value = AdminState(fake.adminId, fake.node, Long.MAX_VALUE - 1)
        assertNull(vm.state.value.propose)
        assertTrue(vm.content.value is Loaded.Idle)
        assertEquals(FederationView.NODES, vm.state.value.view)
        vm.selectView(FederationView.PROPOSALS)
        vm.content.first { it is Loaded.Ready }
        verify(exactly = 2) { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) }
    }

    @Test
    fun `a list response after session end does not restore administrative data`() = runTest {
        val vm = ready()
        val pending = CompletableFuture<io.bosonnetwork.web.PaginatedResult<FederationProposal>>()
        every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns pending
        vm.refresh()
        fake.state.value = null
        pending.complete(page(listOf(proposal(1))))
        assertTrue(vm.content.value is Loaded.Idle)
    }
}
