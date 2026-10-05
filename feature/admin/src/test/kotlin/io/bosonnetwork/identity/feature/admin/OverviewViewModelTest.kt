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
import io.bosonnetwork.director.client.NodeStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Optional
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Overview: read when admin mode opens, cleared when it ends, with federation optional. */
class OverviewViewModelTest {

    private val fake = FakeAdmin()
    private val admin = fake.admin
    private val nodeId = Id.random()

    @Before
    fun setUp() {
        fake.setUp()
        every { admin.nodeStatus } returns done(status())
        every { admin.listUsers(1, 1) } returns done(page(listOf(user()), pageSize = 1, total = 42))
        every { admin.listPlans() } returns done(listOf(plan(1, "Free", price = "0"), plan(2, "Pro"), plan(3, "Old", active = false)))
        every { admin.listFederatedNodes(1, 1) } returns done(page(listOf(federatedNode()), pageSize = 1, total = 3))
        every { admin.listBlacklistedNodes(1, 1) } returns done(page(emptyList(), pageSize = 1, total = 7))
    }

    @After
    fun tearDown() = fake.tearDown()

    private fun status(): NodeStatus = mockk {
        every { this@mockk.nodeId } returns this@OverviewViewModelTest.nodeId
        every { name } returns Optional.of("Home")
        every { software } returns Optional.of("boson")
        every { version } returns Optional.of("3.2.0")
        every { startedAt } returns 5_000
        every { isRunning } returns true
        every { services } returns emptyList()
    }

    private fun viewModel() = fake.track(OverviewViewModel(fake.session, fake.context))

    @Test
    fun `nothing is asked until admin mode opens, then the node and its counts are shown`() = runTest {
        val vm = viewModel()
        assertEquals(Loaded.Idle, vm.content.value)
        verify(exactly = 0) { admin.nodeStatus }

        fake.open()
        val overview = (vm.content.first { it is Loaded.Ready } as Loaded.Ready).value
        assertEquals(nodeId.toString(), overview.nodeId)
        assertEquals("Home", overview.name)
        assertEquals("3.2.0", overview.version)
        assertEquals(42L, overview.users)
        assertEquals(3, overview.plans)
        assertEquals(2, overview.activePlans)
        assertEquals(3L, overview.federatedNodes)
        assertEquals(7L, overview.blacklisted)
    }

    @Test
    fun `a node that doesn't federate shows no federation count`() = runTest {
        every { admin.listFederatedNodes(1, 1) } returns refused(501)
        val vm = viewModel()
        fake.open()
        val overview = (vm.content.first { it is Loaded.Ready } as Loaded.Ready).value
        assertNull(overview.federatedNodes)
    }

    @Test
    fun `ending admin mode clears what was shown`() = runTest {
        val vm = viewModel()
        fake.open()
        vm.content.first { it is Loaded.Ready }
        fake.state.value = null
        assertEquals(Loaded.Idle, vm.content.value)
    }

    @Test
    fun `a failure is shown, and refresh asks again`() = runTest {
        every { admin.nodeStatus } returns refused(500)
        val vm = viewModel()
        fake.open()
        vm.content.first { it is Loaded.Failed }

        every { admin.nodeStatus } returns done(status())
        vm.refresh()
        vm.content.first { it is Loaded.Ready }
    }
}
