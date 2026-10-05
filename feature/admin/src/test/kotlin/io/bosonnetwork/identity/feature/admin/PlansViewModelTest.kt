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

import io.bosonnetwork.director.client.PlanUpdate
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Plans: the list with features, editing names and descriptions, deactivating by typing the name. */
class PlansViewModelTest {

    private val fake = FakeAdmin()
    private val admin = fake.admin

    @Before
    fun setUp() {
        fake.setUp()
        every { admin.listPlans() } returns done(
            listOf(plan(3, "Old", active = false, price = "1"), plan(2, "Pro", price = "20", description = "For teams"), plan(1, "Free", price = "0")),
        )
        every { admin.listFeatures() } returns done(listOf(feature(10, 2, "io.bosonnetwork.ionstore"), feature(11, 1, "io.bosonnetwork.ionstore")))
    }

    @After
    fun tearDown() = fake.tearDown()

    private suspend fun ready(): PlansViewModel {
        val vm = fake.track(PlansViewModel(fake.session, fake.context))
        fake.open()
        vm.content.first { it is Loaded.Ready }
        return vm
    }

    private fun PlansViewModel.plans(): PlansContent = (content.value as Loaded.Ready).value

    @Test
    fun `active plans come first, cheapest first, each with its features`() = runTest {
        val vm = ready()
        assertEquals(listOf("Free", "Pro", "Old"), vm.plans().plans.map { it.name })
        assertTrue(vm.plans().plans.first().free)
        assertEquals(listOf(10), vm.plans().featuresOf(2).map { it.id })
        assertEquals(mapOf("quota" to 100), vm.plans().featuresOf(1).single().feature)
    }

    @Test
    fun `editing sends only what changed`() = runTest {
        val update = slot<PlanUpdate>()
        every { admin.updatePlan(2, capture(update)) } returns ok()
        val vm = ready()
        val pro = vm.plans().plans.first { it.id == 2 }

        vm.startEdit(pro)
        vm.onNameChange("  ")
        vm.save()
        verify(exactly = 0) { admin.updatePlan(any<Int>(), any()) }

        vm.onNameChange("Pro")
        vm.onDescriptionChange("For larger teams")
        vm.save()
        vm.state.first { it.edit == null }
        assertEquals(mapOf("description" to "For larger teams"), update.captured.sent())
        assertEquals("For larger teams", vm.plans().plans.first { it.id == 2 }.description)
    }

    @Test
    fun `deactivating takes typing the plan's name`() = runTest {
        every { admin.deactivatePlan(2) } returns ok()
        val vm = ready()
        val pro = vm.plans().plans.first { it.id == 2 }

        vm.ask(PlanChange.Deactivate(pro))
        vm.confirm("pro")
        verify(exactly = 0) { admin.deactivatePlan(any<Int>()) }

        vm.confirm("Pro")
        vm.state.first { it.change == null }
        verify { admin.deactivatePlan(2) }
        assertFalse(vm.plans().plans.first { it.id == 2 }.active)
    }

    @Test
    fun `an inactive plan can be brought back`() = runTest {
        val update = slot<PlanUpdate>()
        every { admin.updatePlan(3, capture(update)) } returns ok()
        val vm = ready()
        vm.ask(PlanChange.Reactivate(vm.plans().plans.first { it.id == 3 }))
        vm.confirm()
        vm.state.first { it.change == null }
        assertEquals(mapOf("active" to true), update.captured.sent())
        assertTrue(vm.plans().plans.first { it.id == 3 }.active)
    }

    @Test
    fun `a refused change keeps the dialog with the reason`() = runTest {
        every { admin.deactivatePlan(2) } returns refused(409)
        val vm = ready()
        vm.ask(PlanChange.Deactivate(vm.plans().plans.first { it.id == 2 }))
        vm.confirm("Pro")
        val state = vm.state.first { it.error != null }
        assertFalse(state.busy)
        assertTrue(state.change is PlanChange.Deactivate)
        assertTrue(vm.plans().plans.first { it.id == 2 }.active)
    }
}
