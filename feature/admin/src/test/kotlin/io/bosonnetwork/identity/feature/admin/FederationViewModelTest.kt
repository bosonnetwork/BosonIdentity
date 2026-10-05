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
import io.bosonnetwork.director.client.FederatedService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Optional
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Federation: nodes and proposals, proposing by id, removing. */
class FederationViewModelTest {

    private val fake = FakeAdmin()
    private val admin = fake.admin
    private val otherId = Id.random()
    private val other = federatedNode(otherId, name = "Other")

    @Before
    fun setUp() {
        fake.setUp()
        every { fake.context.getString(R.string.adm_fed_propose_blacklisted) } returns "blacklisted"
        every { fake.context.getString(R.string.adm_fed_proposed_sent) } returns "sent"
        every { admin.listFederatedNodes() } returns done(page(listOf(other)))
        every { admin.listFederationProposals() } returns done(page(listOf(proposal(1, proposedAt = 1_000), proposal(2, proposedAt = 2_000))))
    }

    @After
    fun tearDown() = fake.tearDown()

    private suspend fun ready(): FederationViewModel {
        val vm = fake.track(FederationViewModel(fake.session, fake.context))
        fake.open()
        vm.content.first { it is Loaded.Ready }
        return vm
    }

    private fun FederationViewModel.federation(): FederationContent = (content.value as Loaded.Ready).value

    @Test
    fun `nodes and proposals are listed, the latest proposal first`() = runTest {
        val vm = ready()
        assertTrue(vm.federation().enabled)
        assertEquals(listOf("Other"), vm.federation().nodes.map { it.name })
        assertEquals(listOf(2L, 1L), vm.federation().proposals.map { it.id })
    }

    @Test
    fun `a node that doesn't federate says so`() = runTest {
        every { admin.listFederatedNodes() } returns refused(501)
        val vm = ready()
        assertFalse(vm.federation().enabled)
    }

    @Test
    fun `federation is proposed to a node by its id`() = runTest {
        val target = Id.random()
        every { admin.proposeFederation(target) } returns done(Optional.empty())
        val vm = ready()
        vm.startPropose()
        vm.onProposeIdChange("not an id")
        vm.propose()
        verify(exactly = 0) { admin.proposeFederation(any()) }

        vm.onProposeIdChange(" $target ")
        vm.propose()
        assertEquals("sent", vm.state.first { it.proposed != null }.proposed)
        assertNull(vm.state.value.propose)
        verify { admin.proposeFederation(target) }
        // The list is read again, to show the new proposal.
        verify(exactly = 2) { admin.listFederationProposals() }
    }

    @Test
    fun `a blacklisted node can't be proposed to`() = runTest {
        val target = Id.random()
        every { admin.proposeFederation(target) } returns refused(403)
        val vm = ready()
        vm.startPropose()
        vm.onProposeIdChange(target.toString())
        vm.propose()
        assertEquals("blacklisted", vm.state.first { it.error != null }.error)
    }

    @Test
    fun `removing a node takes typing the first characters of its id`() = runTest {
        every { admin.removeFederatedNode(otherId) } returns ok()
        val vm = ready()
        vm.ask(FederationChange.RemoveNode(vm.federation().nodes.single()))
        vm.confirm("x")
        verify(exactly = 0) { admin.removeFederatedNode(any()) }

        vm.confirm(otherId.toString().take(4))
        vm.state.first { it.change == null }
        assertTrue(vm.federation().nodes.isEmpty())
    }

    @Test
    fun `a proposal is removed, and a node's services shown`() = runTest {
        every { admin.removeFederationProposal(2) } returns ok()
        val service = mockk<FederatedService> {
            every { serviceName } returns Optional.of("Ion Store")
            every { serviceId } returns Optional.of("io.bosonnetwork.ionstore")
            every { endpoint } returns Optional.of("https://other.example/ion")
            every { peerId } returns Id.random()
        }
        every { admin.listFederatedServices(otherId) } returns done(listOf(service))
        val vm = ready()

        vm.ask(FederationChange.RemoveProposal(vm.federation().proposals.first()))
        vm.confirm()
        vm.state.first { it.change == null }
        assertEquals(listOf(1L), vm.federation().proposals.map { it.id })

        vm.openServices(vm.federation().nodes.single())
        val services = vm.state.first { it.services?.second is Loaded.Ready }.services!!.second as Loaded.Ready
        assertEquals(listOf(ServiceView("Ion Store", "https://other.example/ion")), services.value)
        vm.closeServices()
        assertNull(vm.state.value.services)
    }
}
