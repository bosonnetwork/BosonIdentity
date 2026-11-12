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
import io.bosonnetwork.web.PaginatedResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FederationProposalsTest {
    private val fake = FakeAdmin()
    private val admin = fake.admin
    private val rows = (21L downTo 1L).map { proposal(it) }

    @Before
    fun setUp() {
        fake.setUp()
        every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns
            done(page(rows.take(20), pageSize = FEDERATION_PAGE, total = 21))
        every { admin.listFederationProposals(null, Role.ANSWER, Status.PENDING, 1, 1) } returns
            done(page(emptyList<FederationProposal>(), pageSize = 1, total = 3))
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

    private fun FederationViewModel.pages() = (content.value as Loaded.Ready).value

    @Test
    fun `server order and totals survive appended overlapping pages`() = runTest {
        every { admin.listFederationProposals(null, null, null, 2, FEDERATION_PAGE) } returns
            done(page(rows.takeLast(2), page = 2, pageSize = FEDERATION_PAGE, total = 21))
        val vm = ready()
        assertEquals(21L, vm.pages().total)
        assertEquals(rows.take(20).map { it.id }, vm.pages().proposals.map { it.id })
        vm.loadMoreProposals()
        assertEquals(rows.map { it.id }, vm.pages().proposals.map { it.id })
        assertFalse(vm.pages().more)
    }

    @Test
    fun `a later page failure retains rows and can be retried without duplicate requests`() = runTest {
        val request = CompletableFuture<PaginatedResult<FederationProposal>>()
        every { admin.listFederationProposals(null, null, null, 2, FEDERATION_PAGE) } returns request
        val vm = ready()
        vm.loadMoreProposals()
        vm.loadMoreProposals()
        verify(exactly = 1) { admin.listFederationProposals(null, null, null, 2, FEDERATION_PAGE) }
        request.completeExceptionally(RuntimeException("network"))
        vm.proposals.first { it.moreError != null }
        assertEquals(20, vm.pages().proposals.size)
        assertEquals(1L, vm.pages().page)
        every { admin.listFederationProposals(null, null, null, 2, FEDERATION_PAGE) } returns
            done(page(rows.takeLast(1), page = 2, pageSize = FEDERATION_PAGE, total = 21))
        vm.loadMoreProposals()
        assertEquals(21, vm.pages().proposals.size)
        assertNull(vm.proposals.value.moreError)
    }

    @Test
    fun `exact search is debounced and combines with server role and status filters`() = runTest {
        val id = rows.last().nodeId
        every { admin.listFederationProposals(null, Role.ANSWER, Status.DECLINED, 1, FEDERATION_PAGE) } returns done(page(emptyList()))
        every { admin.listFederationProposals(id, Role.ANSWER, Status.DECLINED, 1, FEDERATION_PAGE) } returns done(page(rows.takeLast(1)))
        val vm = ready()
        vm.setProposalFilters(Role.ANSWER, Status.DECLINED)
        vm.onProposalQueryChange(" $id ")
        advanceTimeBy(249)
        verify(exactly = 0) { admin.listFederationProposals(id, Role.ANSWER, Status.DECLINED, 1, FEDERATION_PAGE) }
        advanceTimeBy(1)
        runCurrent()
        assertEquals(id.toString(), vm.proposals.value.appliedQuery)
        assertEquals(listOf(rows.last().id), vm.pages().proposals.map { it.id })
        assertFalse(vm.proposals.value.pending)
        verify(exactly = 0) { admin.getFederatedNodeDescriptor(any()) }
        verify(exactly = 1) { admin.listFederationProposals(null, Role.ANSWER, Status.PENDING, 1, 1) }
    }

    @Test
    fun `invalid draft blocks reads and clear immediately reloads current filters`() = runTest {
        every { admin.listFederationProposals(null, Role.OFFER, null, 1, FEDERATION_PAGE) } returns done(page(emptyList()))
        val vm = ready()
        vm.onProposalQueryChange("bad-id")
        vm.setProposalFilters(Role.OFFER, null)
        vm.refreshProposals()
        runCurrent()
        assertTrue(vm.proposals.value.invalid)
        verify(exactly = 0) { admin.listFederationProposals(null, Role.OFFER, null, 1, FEDERATION_PAGE) }
        vm.clearProposalSearch()
        assertFalse(vm.proposals.value.invalid)
        verify(exactly = 1) { admin.listFederationProposals(null, Role.OFFER, null, 1, FEDERATION_PAGE) }
    }

    @Test
    fun `old read success and completion cannot end a new query debounce`() = runTest {
        val old = object : CompletableFuture<PaginatedResult<FederationProposal>>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns old
        val vm = fake.track(FederationViewModel(fake.session, fake.context))
        fake.open()
        vm.selectView(FederationView.PROPOSALS)
        val id = Id.random()
        every { admin.listFederationProposals(id, null, null, 1, FEDERATION_PAGE) } returns done(page(emptyList()))
        vm.onProposalQueryChange(id.toString())
        old.complete(page(rows))
        assertTrue(vm.proposals.value.pending)
        advanceTimeBy(250)
        runCurrent()
        assertFalse(vm.proposals.value.pending)
        assertTrue(vm.pages().proposals.isEmpty())
    }

    @Test
    fun `old failure cannot overwrite the result of new filters`() = runTest {
        val old = object : CompletableFuture<PaginatedResult<FederationProposal>>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns old
        val vm = fake.track(FederationViewModel(fake.session, fake.context))
        fake.open()
        vm.selectView(FederationView.PROPOSALS)
        every { admin.listFederationProposals(null, null, Status.ACCEPTED, 1, FEDERATION_PAGE) } returns done(page(rows.take(1)))
        vm.setProposalFilters(null, Status.ACCEPTED)
        old.completeExceptionally(RuntimeException("late"))
        assertEquals(1, vm.pages().proposals.size)
        assertFalse(vm.proposals.value.refreshing)
    }

    @Test
    fun `refresh cancels paging while retaining rows and late append stays ignored`() = runTest {
        val more = object : CompletableFuture<PaginatedResult<FederationProposal>>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        every { admin.listFederationProposals(null, null, null, 2, FEDERATION_PAGE) } returns more
        val vm = ready()
        vm.loadMoreProposals()
        val fresh = CompletableFuture<PaginatedResult<FederationProposal>>()
        every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns fresh
        vm.refreshProposals()
        assertTrue(vm.proposals.value.refreshing)
        assertEquals(20, vm.pages().proposals.size)
        more.complete(page(rows.takeLast(1), page = 2, pageSize = FEDERATION_PAGE, total = 21))
        assertEquals(20, vm.pages().proposals.size)
        fresh.complete(page(rows.take(1)))
        assertEquals(1, vm.pages().proposals.size)
        assertFalse(vm.proposals.value.refreshing)
    }

    @Test
    fun `an expired later page returns to page one using current filters`() = runTest {
        every { admin.listFederationProposals(null, Role.OFFER, null, 1, FEDERATION_PAGE) } returns
            done(page(rows.take(20), pageSize = FEDERATION_PAGE, total = 21))
        every { admin.listFederationProposals(null, Role.OFFER, null, 2, FEDERATION_PAGE) } answers {
            every { admin.listFederationProposals(null, Role.OFFER, null, 1, FEDERATION_PAGE) } returns done(page(rows.take(1)))
            done(page(emptyList(), page = 2, pageSize = FEDERATION_PAGE, total = 1))
        }
        val vm = ready()
        vm.setProposalFilters(Role.OFFER, null)
        vm.loadMoreProposals()
        assertEquals(1L, vm.pages().page)
        assertEquals(1L, vm.pages().total)
        assertEquals(Role.OFFER, vm.proposals.value.role)
    }

    @Test
    fun `review count is global and the review shortcut clears a node search`() = runTest {
        val id = Id.random()
        every { admin.listFederationProposals(id, null, null, 1, FEDERATION_PAGE) } returns done(page(emptyList()))
        every { admin.listFederationProposals(null, Role.ANSWER, Status.PENDING, 1, FEDERATION_PAGE) } returns done(page(rows.take(3)))
        val vm = ready()
        vm.onProposalQueryChange(id.toString())
        advanceTimeBy(250)
        runCurrent()
        assertEquals(3L, (vm.review.value as Loaded.Ready).value)
        assertEquals(0L, vm.pages().total)
        vm.selectView(FederationView.NODES)
        vm.reviewPending()
        assertEquals(FederationView.PROPOSALS, vm.state.value.view)
        assertEquals("", vm.proposals.value.query)
        assertEquals(Role.ANSWER, vm.proposals.value.role)
        assertEquals(Status.PENDING, vm.proposals.value.status)
        assertEquals(3, vm.pages().proposals.size)
        verify(exactly = 1) { admin.listFederationProposals(null, Role.ANSWER, Status.PENDING, 1, 1) }
    }

    @Test
    fun `count failure is retryable and stale count completion cannot overwrite a newer count`() = runTest {
        every { admin.listFederationProposals(null, Role.ANSWER, Status.PENDING, 1, 1) } returns refused(500)
        val vm = ready()
        assertTrue(vm.review.value is Loaded.Failed)
        val old = object : CompletableFuture<PaginatedResult<FederationProposal>>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        every { admin.listFederationProposals(null, Role.ANSWER, Status.PENDING, 1, 1) } returns old
        vm.refreshReviewCount()
        every { admin.listFederationProposals(null, Role.ANSWER, Status.PENDING, 1, 1) } returns done(page(emptyList(), pageSize = 1, total = 2))
        vm.refreshReviewCount()
        old.complete(page(emptyList(), pageSize = 1, total = 9))
        assertEquals(2L, (vm.review.value as Loaded.Ready).value)
    }

    @Test
    fun `session replacement clears filters and rejects pending list results`() = runTest {
        val pending = object : CompletableFuture<PaginatedResult<FederationProposal>>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        every { admin.listFederationProposals(null, Role.OFFER, null, 1, FEDERATION_PAGE) } returns pending
        val vm = ready()
        vm.setProposalFilters(Role.OFFER, null)
        fake.state.value = AdminState(fake.adminId, fake.node, Long.MAX_VALUE - 1)
        pending.complete(page(rows))
        assertEquals(ProposalListState(), vm.proposals.value)
        assertTrue(vm.content.value is Loaded.Idle)
        fake.state.value = null
        vm.refreshReviewCount()
        vm.reviewPending()
        vm.loadMoreProposals()
        assertTrue(vm.review.value is Loaded.Idle)
        assertEquals(FederationView.NODES, vm.state.value.view)
    }

    @Test
    fun `disabled mode and server failure remain distinct and paging can disable stale rows`() = runTest {
        val vm = ready()
        every { admin.listFederationProposals(null, null, null, 2, FEDERATION_PAGE) } returns refused(501)
        vm.loadMoreProposals()
        assertFalse(vm.pages().enabled)
        every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns refused(500)
        vm.refreshProposals()
        assertTrue(vm.content.value is Loaded.Failed)
        every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns refused(501)
        vm.refreshProposals()
        assertFalse(vm.pages().enabled)
        every { admin.listFederationProposals(null, Role.ANSWER, Status.PENDING, 1, 1) } returns refused(501)
        vm.refreshReviewCount()
        assertTrue(vm.review.value is Loaded.Idle)
    }
    @Test
    fun `a count from a replaced session cannot restore its old total`() = runTest {
        val vm = ready()
        val old = object : CompletableFuture<PaginatedResult<FederationProposal>>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        every { admin.listFederationProposals(null, Role.ANSWER, Status.PENDING, 1, 1) } returns old
        vm.refreshReviewCount()
        every { admin.listFederationProposals(null, Role.ANSWER, Status.PENDING, 1, 1) } returns
            done(page(emptyList(), pageSize = 1, total = 2))
        fake.state.value = AdminState(fake.adminId, fake.node, Long.MAX_VALUE - 1)
        old.complete(page(emptyList(), pageSize = 1, total = 99))
        assertEquals(2L, (vm.review.value as Loaded.Ready).value)
        fake.state.value = null
        assertTrue(vm.review.value is Loaded.Idle)
    }

}
