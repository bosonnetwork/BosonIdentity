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
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Blacklist: the entries, adding (never in one tap) and removing. */
class BlacklistViewModelTest {

    private val fake = FakeAdmin()
    private val admin = fake.admin
    private val badId = Id.random()

    @Before
    fun setUp() {
        fake.setUp()
        every { fake.context.getString(R.string.adm_bl_already) } returns "already"
        every { admin.listBlacklistedNodes() } returns done(
            page(listOf(blacklisted(1, nodeId = badId, createdAt = 1_000, reason = "spam"), blacklisted(2, host = "bad.example", createdAt = 2_000))),
        )
    }

    @After
    fun tearDown() = fake.tearDown()

    private suspend fun ready(): BlacklistViewModel {
        val vm = fake.track(BlacklistViewModel(fake.session, fake.context))
        fake.open()
        vm.content.first { it is Loaded.Ready }
        return vm
    }

    private fun BlacklistViewModel.entries(): List<BlacklistEntry> = (content.value as Loaded.Ready).value

    @Test
    fun `entries are listed, the latest first`() = runTest {
        val vm = ready()
        assertEquals(listOf("bad.example", badId.toString()), vm.entries().map { it.target })
        assertEquals("spam", vm.entries().last().reason)
    }

    @Test
    fun `adding a node asks for its first characters before sending`() = runTest {
        val node = Id.random()
        every { admin.addBlacklistedNode(node, "abuse") } returns done(blacklisted(3, nodeId = node, createdAt = 3_000, reason = "abuse"))
        val vm = ready()
        vm.startAdd()
        vm.onTargetChange("not an id")
        vm.add()
        assertFalse(vm.state.value.add!!.confirming)

        vm.onTargetChange(node.toString())
        vm.onReasonChange(" abuse ")
        vm.add()
        assertTrue(vm.state.value.add!!.confirming)
        vm.add("zzzz")
        verify(exactly = 0) { admin.addBlacklistedNode(any(), any()) }

        vm.add(node.toString().take(4))
        vm.state.first { it.add == null }
        assertEquals(3L, vm.entries().first().id)
    }

    @Test
    fun `a host is added by its name, without a reason`() = runTest {
        every { admin.addBlacklistedHost("evil.example", null) } returns done(blacklisted(4, host = "evil.example", createdAt = 4_000))
        val vm = ready()
        vm.startAdd()
        vm.onKindChange(BlacklistKind.HOST)
        vm.onTargetChange("evil.example")
        vm.add()
        vm.add("evil")
        vm.state.first { it.add == null }
        verify { admin.addBlacklistedHost("evil.example", null) }
    }

    @Test
    fun `changing the target asks for the confirmation again`() = runTest {
        val vm = ready()
        vm.startAdd()
        vm.onKindChange(BlacklistKind.HOST)
        vm.onTargetChange("evil.example")
        vm.add()
        vm.onTargetChange("other.example")
        assertFalse(vm.state.value.add!!.confirming)
    }

    @Test
    fun `an entry already there is reported`() = runTest {
        every { admin.addBlacklistedHost("bad.example", null) } returns refused(409)
        val vm = ready()
        vm.startAdd()
        vm.onKindChange(BlacklistKind.HOST)
        vm.onTargetChange("bad.example")
        vm.add()
        vm.add("bad.")
        assertEquals("already", vm.state.first { it.error != null }.error)
    }

    @Test
    fun `removing an entry lets the node or host back`() = runTest {
        every { admin.removeBlacklistedNode(1L) } returns ok()
        val vm = ready()
        vm.askRemove(vm.entries().first { it.id == 1L })
        vm.remove()
        vm.state.first { it.remove == null }
        verify { admin.removeBlacklistedNode(1L) }
        assertEquals(listOf(2L), vm.entries().map { it.id })
        assertNull(vm.state.value.error)
    }
}
