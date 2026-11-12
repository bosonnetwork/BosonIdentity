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
import io.bosonnetwork.director.client.FederatedHost
import io.bosonnetwork.director.client.FederatedNodeHealth
import io.bosonnetwork.identity.core.boson.AdminState
import io.bosonnetwork.json.Json
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
class FederationObservationsTest {
    private val fake = FakeAdmin()
    private val admin = fake.admin
    private val observations = (1..21).map { health() }
    private val hosts = (1..20).map { host("198.51.100.$it") }

    private fun health(id: Id = Id.random(), proposable: Boolean = false, reason: String? = "NO_RELATIONSHIP",
        outcomes: Map<String, Int> = mapOf("SUCCESS" to 2, "TIMEOUT" to 1)): FederatedNodeHealth = Json.parse(Json.toString(mapOf(
        "nodeId" to id.toString(), "lastSeenAt" to 2000, "lastSuccessAt" to 1000, "lastFailureAt" to 1500,
        "lastFailure" to "TIMEOUT", "lastFailureDetail" to "remote timeout", "consecutiveFailures" to 1,
        "lastHour" to outcomes, "demand" to 3, "lastDemandAt" to 1700, "lastDemandReason" to reason,
        "proposable" to proposable,
    )), FederatedNodeHealth::class.java)

    private fun host(address: String?, verified: Int = 2, failed: Int = 1): FederatedHost = Json.parse(Json.toString(mapOf(
        "host" to address, "verifiedNodes" to verified, "failedNodes" to failed,
        "notFederatingNodes" to 3, "lastCheckedAt" to 2000,
    )), FederatedHost::class.java)

    @Before
    fun setUp() {
        fake.setUp()
        every { admin.listFederatedNodeHealth(null, 1, FEDERATION_PAGE) } returns
            done(page(observations.take(20), pageSize = FEDERATION_PAGE, total = 21))
        every { admin.listFederatedHosts(null, 1, FEDERATION_PAGE) } returns
            done(page(hosts, pageSize = FEDERATION_PAGE, total = 21))
    }

    @After
    fun tearDown() = fake.tearDown()

    private suspend fun activity(): FederationActivityViewModel {
        val vm = fake.track(FederationActivityViewModel(fake.session, fake.context))
        fake.open()
        vm.content.first { it is Loaded.Ready }
        return vm
    }

    private suspend fun hosts(): FederationHostsViewModel {
        val vm = fake.track(FederationHostsViewModel(fake.session, fake.context))
        fake.open()
        vm.content.first { it is Loaded.Ready }
        return vm
    }

    private fun <T : Any> FederationObservationViewModel<T>.pages() = (content.value as Loaded.Ready).value

    @Test
    fun `activity preserves server ordering and observations without relationship lookups`() = runTest {
        val vm = activity()
        assertEquals(21L, vm.pages().total)
        assertEquals(observations.take(20).map { it.nodeId }, vm.pages().items.map { it.nodeId })
        assertEquals(3, vm.pages().items.first().demand)
        verify(exactly = 0) { admin.getFederatedNode(any()) }
        verify(exactly = 0) { admin.getFederatedNodeDescriptor(any()) }
        verify(exactly = 0) { admin.proposeFederation(any(), any()) }
    }

    @Test
    fun `activity exact ID search trims and debounces before the filtered API request`() = runTest {
        val id = observations.last().nodeId
        every { admin.listFederatedNodeHealth(id, 1, FEDERATION_PAGE) } returns done(page(observations.takeLast(1)))
        val vm = activity()
        vm.onQueryChange(" $id ")
        advanceTimeBy(249)
        verify(exactly = 0) { admin.listFederatedNodeHealth(id, 1, FEDERATION_PAGE) }
        advanceTimeBy(1)
        runCurrent()
        assertEquals(id.toString(), vm.state.value.appliedQuery)
        assertEquals(id, vm.pages().items.single().nodeId)
        assertFalse(vm.state.value.pending)
    }

    @Test
    fun `invalid activity input is local and clear reloads immediately`() = runTest {
        val vm = activity()
        vm.onQueryChange("bad-id")
        vm.refreshList()
        advanceTimeBy(500)
        assertTrue(vm.state.value.invalid)
        verify(exactly = 1) { admin.listFederatedNodeHealth(any(), any(), any()) }
        vm.clearSearch()
        assertFalse(vm.state.value.invalid)
        verify(exactly = 2) { admin.listFederatedNodeHealth(null, 1, FEDERATION_PAGE) }
    }

    @Test
    fun `unknown optional outcomes and demand reasons remain absent`() = runTest {
        val record = health(reason = "FUTURE", outcomes = mapOf("SUCCESS" to 4, "FUTURE" to 9))
        every { admin.listFederatedNodeHealth(null, 1, FEDERATION_PAGE) } returns done(page(listOf(record)))
        val vm = activity()
        val row = vm.pages().items.single()
        assertFalse(row.lastDemandReason.isPresent)
        assertEquals(1, row.lastHour.size)
        assertEquals(4, row.lastHour.values.single())
    }

    @Test
    fun `late success cannot clear the pending state of a new draft`() = runTest {
        val old = object : CompletableFuture<PaginatedResult<FederatedNodeHealth>>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        every { admin.listFederatedNodeHealth(null, 1, FEDERATION_PAGE) } returns old
        val vm = fake.track(FederationActivityViewModel(fake.session, fake.context))
        fake.open()
        val id = Id.random()
        every { admin.listFederatedNodeHealth(id, 1, FEDERATION_PAGE) } returns done(page(emptyList()))
        vm.onQueryChange(id.toString())
        old.complete(page(observations))
        assertTrue(vm.state.value.pending)
        advanceTimeBy(250)
        runCurrent()
        assertTrue(vm.pages().items.isEmpty())
        assertFalse(vm.state.value.pending)
    }

    @Test
    fun `old activity failure cannot replace a new successful query`() = runTest {
        val old = object : CompletableFuture<PaginatedResult<FederatedNodeHealth>>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        every { admin.listFederatedNodeHealth(null, 1, FEDERATION_PAGE) } returns old
        val vm = fake.track(FederationActivityViewModel(fake.session, fake.context))
        fake.open()
        val id = observations.last().nodeId
        every { admin.listFederatedNodeHealth(id, 1, FEDERATION_PAGE) } returns done(page(observations.takeLast(1)))
        vm.onQueryChange(id.toString())
        advanceTimeBy(250)
        runCurrent()
        old.completeExceptionally(RuntimeException("late"))
        assertEquals(id, vm.pages().items.single().nodeId)
    }

    @Test
    fun `activity append deduplicates overlap and prevents double loads`() = runTest {
        val next = CompletableFuture<PaginatedResult<FederatedNodeHealth>>()
        every { admin.listFederatedNodeHealth(null, 2, FEDERATION_PAGE) } returns next
        val vm = activity()
        vm.loadMore()
        vm.loadMore()
        verify(exactly = 1) { admin.listFederatedNodeHealth(null, 2, FEDERATION_PAGE) }
        next.complete(page(observations.takeLast(2), page = 2, pageSize = FEDERATION_PAGE, total = 21))
        assertEquals(observations.map { it.nodeId }, vm.pages().items.map { it.nodeId })
        assertFalse(vm.pages().more)
    }

    @Test
    fun `paging failures retain data and allow an explicit retry`() = runTest {
        every { admin.listFederatedNodeHealth(null, 2, FEDERATION_PAGE) } returns refused(500)
        val vm = activity()
        vm.loadMore()
        assertEquals(20, vm.pages().items.size)
        assertTrue(vm.pages().more)
        assertTrue(vm.state.value.moreError != null)
        every { admin.listFederatedNodeHealth(null, 2, FEDERATION_PAGE) } returns
            done(page(observations.takeLast(1), page = 2, pageSize = FEDERATION_PAGE, total = 21))
        vm.loadMore()
        assertEquals(21, vm.pages().items.size)
        assertNull(vm.state.value.moreError)
    }

    @Test
    fun `refresh retains rows and rejects a late append`() = runTest {
        val next = object : CompletableFuture<PaginatedResult<FederatedNodeHealth>>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        every { admin.listFederatedNodeHealth(null, 2, FEDERATION_PAGE) } returns next
        val vm = activity()
        vm.loadMore()
        val fresh = CompletableFuture<PaginatedResult<FederatedNodeHealth>>()
        every { admin.listFederatedNodeHealth(null, 1, FEDERATION_PAGE) } returns fresh
        vm.refreshList()
        assertTrue(vm.state.value.refreshing)
        assertEquals(20, vm.pages().items.size)
        next.complete(page(observations.takeLast(1), page = 2, pageSize = FEDERATION_PAGE, total = 21))
        assertEquals(20, vm.pages().items.size)
        fresh.complete(page(observations.take(1)))
        assertEquals(1, vm.pages().items.size)
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun `observation expiry on a later page reloads the same filtered first page`() = runTest {
        val id = Id.random()
        every { admin.listFederatedNodeHealth(id, 1, FEDERATION_PAGE) } returns
            done(page(observations.take(20), pageSize = FEDERATION_PAGE, total = 21))
        every { admin.listFederatedNodeHealth(id, 2, FEDERATION_PAGE) } answers {
            every { admin.listFederatedNodeHealth(id, 1, FEDERATION_PAGE) } returns done(page(emptyList()))
            done(page(emptyList(), page = 2, pageSize = FEDERATION_PAGE, total = 0))
        }
        val vm = activity()
        vm.onQueryChange(id.toString())
        advanceTimeBy(250)
        runCurrent()
        vm.loadMore()
        assertEquals(id.toString(), vm.state.value.appliedQuery)
        assertEquals(1L, vm.pages().page)
        assertTrue(vm.pages().items.isEmpty())
    }

    @Test
    fun `disabled APIs are distinct from errors and remove actionable stale pages`() = runTest {
        val vm = activity()
        every { admin.listFederatedNodeHealth(null, 2, FEDERATION_PAGE) } returns refused(501)
        vm.loadMore()
        assertFalse(vm.pages().enabled)
        every { admin.listFederatedNodeHealth(null, 1, FEDERATION_PAGE) } returns refused(500)
        vm.refreshList()
        assertTrue(vm.content.value is Loaded.Failed)
        every { admin.listFederatedNodeHealth(null, 1, FEDERATION_PAGE) } returns refused(501)
        vm.refreshList()
        assertFalse(vm.pages().enabled)
    }

    @Test
    fun `host observations preserve distinct counts and nullable destinations`() = runTest {
        val ipv6 = host("2001:db8:1:2::/64", verified = 5, failed = 2)
        val unresolved = host(null, verified = 0, failed = 4)
        every { admin.listFederatedHosts(null, 1, FEDERATION_PAGE) } returns done(page(listOf(ipv6, unresolved)))
        val vm = hosts()
        assertEquals(2L, vm.pages().total)
        assertEquals("2001:db8:1:2::/64", vm.pages().items.first().host.orElse(null))
        assertEquals(5, vm.pages().items.first().verifiedNodes)
        assertEquals(2, vm.pages().items.first().failedNodes)
        assertEquals(3, vm.pages().items.first().notFederatingNodes)
        assertFalse(vm.pages().items.last().host.isPresent)
    }

    @Test
    fun `host search preserves case and literal wildcard and IPv6 text`() = runTest {
        val query = "A_%[2001:DB8::]/64"
        every { admin.listFederatedHosts(query, 1, FEDERATION_PAGE) } returns done(page(emptyList()))
        val vm = hosts()
        vm.onQueryChange(" $query ")
        assertFalse(vm.state.value.invalid)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(query, vm.state.value.appliedQuery)
        verify(exactly = 1) { admin.listFederatedHosts(query, 1, FEDERATION_PAGE) }
        verify(exactly = 0) { admin.getFederatedNodeDescriptor(any()) }
    }

    @Test
    fun `unresolved shortcut uses the API term without localized text and clear restores all hosts`() = runTest {
        every { fake.context.getString(R.string.adm_fed_unresolved_host) } returns "localized label"
        every { admin.listFederatedHosts(UNRESOLVED_HOST_QUERY, 1, FEDERATION_PAGE) } returns done(page(listOf(host(null))))
        val vm = hosts()
        vm.showUnresolved()
        assertEquals(UNRESOLVED_HOST_QUERY, vm.state.value.query)
        assertFalse(vm.pages().items.single().host.isPresent)
        verify(exactly = 1) { admin.listFederatedHosts("Unresolved host", 1, FEDERATION_PAGE) }
        vm.clearSearch()
        assertEquals("", vm.state.value.query)
        assertEquals(20, vm.pages().items.size)
    }

    @Test
    fun `nullable host keys are deduplicated across pages`() = runTest {
        val initial = listOf(hosts.first(), host(null))
        every { admin.listFederatedHosts(null, 1, FEDERATION_PAGE) } returns done(page(initial, pageSize = FEDERATION_PAGE, total = 21))
        every { admin.listFederatedHosts(null, 2, FEDERATION_PAGE) } returns
            done(page(listOf(host(null), hosts.last()), page = 2, pageSize = FEDERATION_PAGE, total = 21))
        val vm = hosts()
        vm.loadMore()
        assertEquals(3, vm.pages().items.size)
        assertEquals(1, vm.pages().items.count { !it.host.isPresent })
    }

    @Test
    fun `all four views retain their independent search state`() = runTest {
        every { admin.listFederationProposals(any(), any(), any(), any(), any()) } returns done(page(emptyList()))
        every { admin.listFederatedNodes(any(), any(), any()) } returns done(page(emptyList()))
        every { admin.listFederatedNodeHealth(any(), any(), any()) } returns done(page(emptyList()))
        every { admin.listFederatedHosts(any(), any(), any()) } returns done(page(emptyList()))
        val activity = activity()
        val hosts = hosts()
        val actions = fake.track(FederationViewModel(fake.session, fake.context))
        val nodes = fake.track(FederationNodesViewModel(fake.session, fake.context))
        val ids = (1..3).map { Id.random().toString() }
        nodes.onQueryChange(ids[0])
        actions.onProposalQueryChange(ids[1])
        activity.onQueryChange(ids[2])
        hosts.onQueryChange("198.51")
        advanceTimeBy(250)
        runCurrent()
        for (view in FederationView.entries) actions.selectView(view)
        assertEquals(ids[0], nodes.state.value.query)
        assertEquals(ids[1], actions.proposals.value.query)
        assertEquals(ids[2], activity.state.value.query)
        assertEquals("198.51", hosts.state.value.query)
    }

    @Test
    fun `session replacement and end clear both observation views and ignore old results`() = runTest {
        val activity = activity()
        val hosts = hosts()
        val old = object : CompletableFuture<PaginatedResult<FederatedHost>>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        every { admin.listFederatedHosts("old", 1, FEDERATION_PAGE) } returns old
        hosts.onQueryChange("old")
        advanceTimeBy(250)
        runCurrent()
        activity.onQueryChange("bad-id")
        fake.state.value = AdminState(fake.adminId, fake.node, Long.MAX_VALUE - 1)
        old.complete(page(listOf(host("old"))))
        assertEquals(ObservationListState(), activity.state.value)
        assertEquals(ObservationListState(), hosts.state.value)
        assertEquals(20, hosts.pages().items.size)
        fake.state.value = null
        assertTrue(activity.content.value is Loaded.Idle)
        assertTrue(hosts.content.value is Loaded.Idle)
    }

    @Test
    fun `observation requests cannot start without an admin session`() = runTest {
        val a = fake.track(FederationActivityViewModel(fake.session, fake.context))
        val h = fake.track(FederationHostsViewModel(fake.session, fake.context))
        a.onQueryChange(Id.random().toString())
        a.refreshList()
        a.loadMore()
        h.showUnresolved()
        h.refreshList()
        h.loadMore()
        assertEquals(ObservationListState(), a.state.value)
        assertEquals(ObservationListState(), h.state.value)
        verify(exactly = 0) { admin.listFederatedNodeHealth(any(), any(), any()) }
        verify(exactly = 0) { admin.listFederatedHosts(any(), any(), any()) }
    }

    @Test
    fun `only server-proposable activity opens the reviewed proposal form`() = runTest {
        every { admin.listFederationProposals(any(), any(), any(), any(), any()) } returns done(page(emptyList()))
        val vm = fake.track(FederationViewModel(fake.session, fake.context))
        fake.open()
        vm.selectView(FederationView.ACTIVITY)
        vm.startActivityProposal(health(proposable = false))
        assertNull(vm.state.value.propose)
        val record = health(proposable = true)
        vm.startActivityProposal(record)
        assertEquals(record.nodeId.toString(), vm.state.value.propose!!.nodeId)
        assertEquals("", vm.state.value.propose!!.note)
        verify(exactly = 0) { admin.proposeFederation(any(), any()) }
        fake.state.value = null
        vm.startActivityProposal(record)
        assertNull(vm.state.value.propose)
    }
}
