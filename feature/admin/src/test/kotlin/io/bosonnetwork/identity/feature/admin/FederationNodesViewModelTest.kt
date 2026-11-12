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
import io.bosonnetwork.json.Json
import io.bosonnetwork.service.Admission
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FederationNodesViewModelTest {
    private val fake = FakeAdmin()
    private val admin = fake.admin
    private val rows = (1..21).map { federatedNode() }

    @Before
    fun setUp() {
        fake.setUp()
        every { admin.listFederatedNodes(null, 1, FEDERATION_PAGE) } returns done(page(rows.take(20), pageSize = FEDERATION_PAGE, total = 21))
    }

    @After
    fun tearDown() = fake.tearDown()

    private suspend fun ready(): FederationNodesViewModel {
        val vm = fake.track(FederationNodesViewModel(fake.session, fake.context))
        fake.open()
        vm.content.first { it is Loaded.Ready }
        return vm
    }

    private fun FederationNodesViewModel.pages(): NodePages = (content.value as Loaded.Ready).value

    @Test
    fun `the first page preserves server order, totals and independent admission information`() = runTest {
        val row = federatedNode(state = FederatedNode.State.ACTIVE, admission = Admission.Reason.BLOCKED,
            reach = Admission.Reason.PENDING, note = "operator note",
            health = mapOf("nodeId" to rows.first().id.toString(), "lastSeenAt" to 900, "consecutiveFailures" to 2))
        every { admin.listFederatedNodes(null, 1, FEDERATION_PAGE) } returns done(page(listOf(row) + rows.take(19), pageSize = FEDERATION_PAGE, total = 21))
        val vm = ready()
        assertEquals(20, vm.pages().nodes.size)
        assertEquals(21L, vm.pages().total)
        assertTrue(vm.pages().more)
        val node = vm.pages().nodes.first()
        assertEquals(row.id.toString(), node.id)
        assertEquals(FederatedNode.State.ACTIVE, node.state)
        assertEquals(Admission.Reason.BLOCKED, node.admission)
        assertEquals(Admission.Reason.PENDING, node.reach)
        assertEquals("operator note", node.note)
        assertEquals("admin", node.origin)
        assertEquals(500L, node.createdAt)
        assertEquals(1_000L, node.updatedAt)
        assertTrue(node.health!!.isFailing)
        verify(exactly = 0) { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) }
        verify(exactly = 0) { admin.getFederatedNode(any()) }
        verify(exactly = 0) { admin.getFederatedNodeDescriptor(any()) }
    }

    @Test
    fun `load more appends in server order and deduplicates overlapping pages`() = runTest {
        every { admin.listFederatedNodes(null, 2, FEDERATION_PAGE) } returns done(page(listOf(rows.first(), rows.last()), page = 2, pageSize = FEDERATION_PAGE, total = 21))
        val vm = ready()
        vm.loadMore()
        assertEquals(rows.map { it.id.toString() }, vm.pages().nodes.map { it.id })
        assertEquals(2L, vm.pages().page)
        assertFalse(vm.pages().more)
        vm.loadMore()
        verify(exactly = 1) { admin.listFederatedNodes(null, 2, FEDERATION_PAGE) }
    }

    @Test
    fun `a later-page failure retains rows and retries the same page`() = runTest {
        every { admin.listFederatedNodes(null, 2, FEDERATION_PAGE) } returns refused(500)
        val vm = ready()
        vm.loadMore()
        assertEquals(20, vm.pages().nodes.size)
        assertEquals(1L, vm.pages().page)
        assertNotNull(vm.state.value.moreError)
        assertFalse(vm.state.value.loadingMore)
        every { admin.listFederatedNodes(null, 2, FEDERATION_PAGE) } returns done(page(rows.takeLast(1), page = 2, pageSize = FEDERATION_PAGE, total = 21))
        vm.loadMore()
        assertEquals(21, vm.pages().nodes.size)
        assertNull(vm.state.value.moreError)
    }

    @Test
    fun `double load-more taps share the outstanding request`() = runTest {
        val pending = CompletableFuture<PaginatedResult<FederatedNode>>()
        every { admin.listFederatedNodes(null, 2, FEDERATION_PAGE) } returns pending
        val vm = ready()
        vm.loadMore()
        vm.loadMore()
        assertTrue(vm.state.value.loadingMore)
        verify(exactly = 1) { admin.listFederatedNodes(null, 2, FEDERATION_PAGE) }
        pending.complete(page(rows.takeLast(1), page = 2, pageSize = FEDERATION_PAGE, total = 21))
        assertFalse(vm.state.value.loadingMore)
    }

    @Test
    fun `exact search finds an item beyond the original first page and resets its totals`() = runTest {
        val target = rows.last()
        every { admin.listFederatedNodes(target.id, 1, FEDERATION_PAGE) } returns done(page(listOf(target), pageSize = FEDERATION_PAGE))
        val vm = ready()
        vm.onQueryChange(" ${target.id} ")
        assertTrue(vm.state.value.pending)
        advanceTimeBy(249)
        verify(exactly = 0) { admin.listFederatedNodes(target.id, 1, FEDERATION_PAGE) }
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(target.id.toString()), vm.pages().nodes.map { it.id })
        assertEquals(1L, vm.pages().page)
        assertEquals(1L, vm.pages().total)
        assertEquals(target.id.toString(), vm.state.value.appliedQuery)
        assertFalse(vm.state.value.pending)
    }

    @Test
    fun `typing invalid text starts no lookup and clearing restores the full list immediately`() = runTest {
        val vm = ready()
        vm.onQueryChange("incomplete")
        advanceTimeBy(300)
        runCurrent()
        assertTrue(vm.state.value.invalid)
        assertFalse(vm.state.value.pending)
        verify(exactly = 1) { admin.listFederatedNodes(any(), any(), any()) }
        vm.clearSearch()
        assertFalse(vm.state.value.invalid)
        assertEquals("", vm.state.value.query)
        assertEquals(21L, vm.pages().total)
        verify(exactly = 2) { admin.listFederatedNodes(null, 1, FEDERATION_PAGE) }
    }

    @Test
    fun `an unknown full ID returns no relationship without using the unrestricted node lookup`() = runTest {
        val id = Id.random()
        every { admin.listFederatedNodes(id, 1, FEDERATION_PAGE) } returns done(page(emptyList(), pageSize = FEDERATION_PAGE))
        val vm = ready()
        vm.onQueryChange(id.toString())
        advanceTimeBy(250)
        runCurrent()
        assertTrue(vm.pages().nodes.isEmpty())
        assertEquals(0L, vm.pages().total)
        assertFalse(vm.state.value.invalid)
        verify(exactly = 0) { admin.getFederatedNode(any()) }
    }

    @Test
    fun `clearing an outstanding filtered request restores the full list and ignores its late result`() = runTest {
        val id = rows.last().id
        val pending = CompletableFuture<PaginatedResult<FederatedNode>>()
        every { admin.listFederatedNodes(id, 1, FEDERATION_PAGE) } returns pending
        val vm = ready()
        vm.onQueryChange(id.toString())
        advanceTimeBy(250)
        runCurrent()
        vm.loadMore()
        verify(exactly = 0) { admin.listFederatedNodes(null, 2, FEDERATION_PAGE) }
        vm.clearSearch()
        pending.complete(page(rows.takeLast(1), pageSize = FEDERATION_PAGE))
        assertEquals("", vm.state.value.query)
        assertEquals("", vm.state.value.appliedQuery)
        assertEquals(21L, vm.pages().total)
        assertEquals(20, vm.pages().nodes.size)
        assertFalse(vm.state.value.pending)
    }

    @Test
    fun `switching views during debounce retains the query and completes its own read`() = runTest {
        val target = rows.last()
        every { admin.listFederatedNodes(target.id, 1, FEDERATION_PAGE) } returns done(page(listOf(target), pageSize = FEDERATION_PAGE))
        every { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) } returns done(page(emptyList()))
        val nodes = ready()
        val actions = fake.track(FederationViewModel(fake.session, fake.context))
        nodes.onQueryChange(target.id.toString())
        actions.selectView(FederationView.PROPOSALS)
        assertTrue(nodes.state.value.pending)
        advanceTimeBy(250)
        runCurrent()
        actions.selectView(FederationView.NODES)
        assertEquals(target.id.toString(), nodes.state.value.query)
        assertEquals(listOf(target.id.toString()), nodes.pages().nodes.map { it.id })
        verify(exactly = 1) { admin.listFederatedNodes(target.id, 1, FEDERATION_PAGE) }
        verify(exactly = 1) { admin.listFederationProposals(null, null, null, 1, FEDERATION_PAGE) }
    }

    @Test
    fun `a first-page error preserves the search and can be retried`() = runTest {
        val id = rows.last().id
        every { admin.listFederatedNodes(id, 1, FEDERATION_PAGE) } returns refused(500)
        val vm = ready()
        vm.onQueryChange(id.toString())
        advanceTimeBy(250)
        runCurrent()
        assertTrue(vm.content.value is Loaded.Failed)
        assertEquals(id.toString(), vm.state.value.query)
        assertFalse(vm.state.value.pending)
        every { admin.listFederatedNodes(id, 1, FEDERATION_PAGE) } returns done(page(rows.takeLast(1), pageSize = FEDERATION_PAGE))
        vm.refreshNodes()
        assertEquals(listOf(id.toString()), vm.pages().nodes.map { it.id })
    }

    @Test
    fun `old page completions cannot overwrite a new draft or its loading state`() = runTest {
        val old = CompletableFuture<PaginatedResult<FederatedNode>>()
        every { admin.listFederatedNodes(null, 2, FEDERATION_PAGE) } returns old
        val target = rows.last()
        val fresh = CompletableFuture<PaginatedResult<FederatedNode>>()
        every { admin.listFederatedNodes(target.id, 1, FEDERATION_PAGE) } returns fresh
        val vm = ready()
        vm.loadMore()
        vm.onQueryChange(target.id.toString())
        old.complete(page(rows.takeLast(1), page = 2, pageSize = FEDERATION_PAGE, total = 21))
        assertTrue(vm.state.value.pending)
        assertFalse(vm.state.value.loadingMore)
        assertNull(vm.state.value.moreError)
        advanceTimeBy(250)
        runCurrent()
        assertTrue(vm.state.value.pending)
        fresh.complete(page(listOf(target), pageSize = FEDERATION_PAGE))
        assertFalse(vm.state.value.pending)
        assertEquals(listOf(target.id.toString()), vm.pages().nodes.map { it.id })
    }

    @Test
    fun `an old failure stays silent after a newer query succeeds`() = runTest {
        val oldId = rows.first().id
        val next = rows.last()
        val old = CompletableFuture<PaginatedResult<FederatedNode>>()
        every { admin.listFederatedNodes(oldId, 1, FEDERATION_PAGE) } returns old
        every { admin.listFederatedNodes(next.id, 1, FEDERATION_PAGE) } returns done(page(listOf(next), pageSize = FEDERATION_PAGE))
        val vm = ready()
        vm.onQueryChange(oldId.toString())
        advanceTimeBy(250)
        runCurrent()
        vm.onQueryChange(next.id.toString())
        advanceTimeBy(250)
        runCurrent()
        old.completeExceptionally(IllegalStateException("old failure"))
        assertEquals(listOf(next.id.toString()), vm.pages().nodes.map { it.id })
        assertFalse(vm.state.value.pending)
    }

    @Test
    fun `refresh cancels an old page and starts at page one with same-query rows visible`() = runTest {
        val pending = CompletableFuture<PaginatedResult<FederatedNode>>()
        every { admin.listFederatedNodes(null, 2, FEDERATION_PAGE) } returns pending
        val vm = ready()
        vm.loadMore()
        val first = CompletableFuture<PaginatedResult<FederatedNode>>()
        every { admin.listFederatedNodes(null, 1, FEDERATION_PAGE) } returns first
        vm.refreshNodes()
        assertTrue(vm.state.value.refreshing)
        assertFalse(vm.state.value.pending)
        assertEquals(20, vm.pages().nodes.size)
        pending.complete(page(rows.takeLast(1), page = 2, pageSize = FEDERATION_PAGE, total = 21))
        assertTrue(vm.state.value.refreshing)
        first.complete(page(rows.take(20), pageSize = FEDERATION_PAGE, total = 21))
        assertFalse(vm.state.value.refreshing)
        assertEquals(1L, vm.pages().page)
    }

    @Test
    fun `a page that disappeared falls back to the first page and keeps refreshing until completion`() = runTest {
        every { admin.listFederatedNodes(null, 2, FEDERATION_PAGE) } returns done(page(emptyList(), page = 2, pageSize = FEDERATION_PAGE, total = 20))
        val vm = ready()
        val refreshed = CompletableFuture<PaginatedResult<FederatedNode>>()
        every { admin.listFederatedNodes(null, 1, FEDERATION_PAGE) } returns refreshed
        vm.loadMore()
        assertTrue(vm.state.value.refreshing)
        assertFalse(vm.state.value.loadingMore)
        refreshed.complete(page(rows.take(20), pageSize = FEDERATION_PAGE, total = 20))
        assertFalse(vm.state.value.refreshing)
        assertEquals(1L, vm.pages().page)
        assertFalse(vm.pages().more)
    }

    @Test
    fun `disabled federation is distinct from a failed first-page read`() = runTest {
        every { admin.listFederatedNodes(null, 1, FEDERATION_PAGE) } returns refused(501)
        val vm = ready()
        assertFalse(vm.pages().enabled)
        every { admin.listFederatedNodes(null, 1, FEDERATION_PAGE) } returns refused(500)
        vm.refreshNodes()
        assertTrue(vm.content.value is Loaded.Failed)
    }

    @Test
    fun `a disabled API during paging removes actionable stale rows`() = runTest {
        every { admin.listFederatedNodes(null, 2, FEDERATION_PAGE) } returns refused(501)
        val vm = ready()
        vm.loadMore()
        assertFalse(vm.pages().enabled)
        assertTrue(vm.pages().nodes.isEmpty())
        assertFalse(vm.state.value.loadingMore)
        assertNull(vm.state.value.moreError)
    }

    @Test
    fun `unknown optional state and health stay absent`() = runTest {
        val id = Id.random()
        val row = Json.parse("""{"nodeId":"$id","state":"future","admission":"FUTURE"}""", FederatedNode::class.java)
        every { admin.listFederatedNodes(null, 1, FEDERATION_PAGE) } returns done(page(listOf(row), pageSize = FEDERATION_PAGE))
        val node = ready().pages().nodes.single()
        assertNull(node.state)
        assertNull(node.admission)
        assertNull(node.health)
    }

    @Test
    fun `ending and replacing sessions discard pending searches and late list responses`() = runTest {
        val vm = ready()
        vm.onQueryChange(rows.last().id.toString())
        fake.state.value = null
        advanceTimeBy(300)
        runCurrent()
        assertEquals(FederationNodesUiState(), vm.state.value)
        assertTrue(vm.content.value is Loaded.Idle)
        verify(exactly = 1) { admin.listFederatedNodes(any(), any(), any()) }
        fake.open()
        vm.onQueryChange("invalid")
        fake.state.value = AdminState(fake.adminId, fake.node, Long.MAX_VALUE - 1)
        assertEquals("", vm.state.value.query)
        assertTrue(vm.content.value is Loaded.Ready)
    }

    @Test
    fun `no list or paging request starts without admin mode`() = runTest {
        val vm = fake.track(FederationNodesViewModel(fake.session, fake.context))
        vm.onQueryChange(rows.last().id.toString())
        vm.refreshNodes()
        vm.loadMore()
        advanceTimeBy(300)
        runCurrent()
        verify(exactly = 0) { admin.listFederatedNodes(any(), any(), any()) }
    }
}
