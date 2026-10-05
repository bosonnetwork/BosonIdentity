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
import io.bosonnetwork.director.client.Payment
import io.bosonnetwork.director.client.PaymentUpdate
import io.bosonnetwork.director.client.Sort
import io.bosonnetwork.director.client.UserUpdate
import io.mockk.every
import io.mockk.slot
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

/** Users: the paged list and its search, a user's detail, and the changes made there. */
class UsersViewModelTest {

    private val fake = FakeAdmin()
    private val admin = fake.admin

    private val aliceId = Id.random()
    private val alice = user(aliceId, name = "Alice", email = "alice@example.com", createdAt = 3_000)
    private val bob = user(name = "Bob", email = "bob@example.com", createdAt = 2_000)
    private val carol = user(name = "Carol", createdAt = 1_000)
    private val phoneId = Id.random()
    private val phone = device(phoneId, name = "Pixel", lastSeen = 5_000)

    @Before
    fun setUp() {
        fake.setUp()
        every { admin.listUsers(1, USERS_PAGE, Sort.desc("createdAt")) } returns
            done(page(listOf(alice, bob), page = 1, total = USERS_PAGE + 1))
        every { admin.listUsers(2, USERS_PAGE, Sort.desc("createdAt")) } returns
            done(page(listOf(carol), page = 2, total = USERS_PAGE + 1))
        every { admin.getUser(aliceId) } returns done(Optional.of(alice))
        every { admin.listDevices(aliceId) } returns done(listOf(phone))
        every { admin.listSubscriptions(aliceId) } returns done(page(listOf(subscription())))
        every { admin.listPayments(aliceId) } returns
            done(page(listOf(payment(1), payment(2, transaction = false), payment(3, status = Payment.Status.CONFIRMED))))
    }

    @After
    fun tearDown() = fake.tearDown()

    private suspend fun ready(): UsersViewModel {
        val vm = fake.track(UsersViewModel(fake.session, fake.context))
        fake.open()
        vm.content.first { it is Loaded.Ready }
        return vm
    }

    private fun UsersViewModel.pages(): UserPages = (content.value as Loaded.Ready).value

    private suspend fun UsersViewModel.detail(): UserDetail =
        (state.first { it.detail is Loaded.Ready && !it.busy }.detail as Loaded.Ready).value

    @Test
    fun `the first page is listed, and more pages are added`() = runTest {
        val vm = ready()
        assertEquals(listOf("Alice", "Bob"), vm.pages().users.map { it.name })
        assertTrue(vm.pages().more)

        vm.loadMore()
        vm.state.first { !it.loadingMore }
        assertEquals(listOf("Alice", "Bob", "Carol"), vm.pages().users.map { it.name })
        assertFalse(vm.pages().more)
    }

    @Test
    fun `the search filters the pages read, and a full id is looked up`() = runTest {
        val vm = ready()
        vm.onQueryChange("BOB@")
        assertEquals(listOf("Bob"), vm.visible(vm.pages(), vm.state.value).map { it.name })

        val daveId = Id.random()
        every { admin.getUser(daveId) } returns done(Optional.of(user(daveId, name = "Dave")))
        vm.onQueryChange(daveId.toString())
        vm.state.first { it.found != null }
        assertEquals(listOf("Dave"), vm.visible(vm.pages(), vm.state.value).map { it.name })
    }

    @Test
    fun `a user's detail shows devices, subscriptions and payments`() = runTest {
        val vm = ready()
        vm.open(aliceId.toString())
        val detail = vm.detail()
        assertEquals("Alice", detail.user.name)
        assertEquals(listOf("Pixel"), detail.devices.map { it.name })
        assertEquals(1, detail.subscriptions.size)
        val payments = detail.payments.associateBy { it.id }
        assertTrue(payments.getValue(1).canConfirm)
        assertTrue(payments.getValue(1).canCancel)
        // A pending payment whose transaction wasn't submitted is settled on the web.
        assertFalse(payments.getValue(2).canConfirm)
        assertFalse(payments.getValue(3).canCancel)

        vm.close()
        assertNull(vm.state.value.selected)
    }

    @Test
    fun `making a user an administrator sends the flag, after confirming`() = runTest {
        val update = slot<UserUpdate>()
        every { admin.updateUser(aliceId, capture(update)) } returns ok()
        val vm = ready()
        vm.open(aliceId.toString())
        vm.detail()

        vm.ask(UserChange.SetAdmin(true))
        verify(exactly = 0) { admin.updateUser(any(), any()) }
        vm.confirm()
        vm.state.first { it.change == null && !it.busy }
        assertEquals(mapOf("admin" to true), update.captured.sent())
        assertTrue(vm.pages().users.first { it.id == aliceId.toString() }.admin)
    }

    @Test
    fun `removing a user takes typing the first characters of their id`() = runTest {
        every { admin.removeUser(aliceId) } returns ok()
        val vm = ready()
        vm.open(aliceId.toString())
        vm.detail()

        vm.ask(UserChange.Remove)
        vm.confirm("")
        vm.confirm("nope")
        verify(exactly = 0) { admin.removeUser(any()) }

        vm.confirm(aliceId.toString().take(4))
        vm.state.first { it.selected == null }
        verify { admin.removeUser(aliceId) }
        assertEquals(listOf("Bob"), vm.pages().users.map { it.name })
        assertEquals(USERS_PAGE, vm.pages().total)
    }

    @Test
    fun `an administrator can't remove themselves or drop their own role`() = runTest {
        val meId = Id.of(fake.adminId)
        every { admin.getUser(meId) } returns done(Optional.of(user(meId, name = "Me", admin = true)))
        every { admin.listDevices(meId) } returns done(emptyList())
        every { admin.listSubscriptions(meId) } returns done(page(emptyList()))
        every { admin.listPayments(meId) } returns done(page(emptyList()))
        val vm = ready()
        vm.open(meId.toString())
        assertTrue(vm.detail().user.you)

        vm.ask(UserChange.Remove)
        assertNull(vm.state.value.change)
        vm.ask(UserChange.SetAdmin(false))
        assertNull(vm.state.value.change)
    }

    @Test
    fun `removing a device, confirming and cancelling payments`() = runTest {
        every { admin.removeDevice(phoneId) } returns ok()
        val confirmation = slot<PaymentUpdate>()
        every { admin.updatePayment(1, capture(confirmation)) } returns ok()
        every { admin.cancelPayment(2) } returns ok()
        val vm = ready()
        vm.open(aliceId.toString())
        val detail = vm.detail()

        vm.ask(UserChange.RemoveDevice(detail.devices.single()))
        vm.confirm()
        vm.state.first { it.change == null }
        verify { admin.removeDevice(phoneId) }

        vm.ask(UserChange.ConfirmPayment(detail.payments.first { it.id == 1L }))
        vm.confirm()
        vm.state.first { it.change == null }
        // The node wants the submitted transaction repeated with the confirmation.
        assertEquals(
            mapOf(
                "status" to Payment.Status.CONFIRMED,
                "network" to "tron",
                "token" to "USDT",
                "tokenAmount" to "9.90",
                "walletFrom" to "Tfrom",
                "transactionId" to "tx1",
                "walletTo" to "Tto",
            ),
            confirmation.captured.sent(),
        )

        vm.ask(UserChange.CancelPayment(detail.payments.first { it.id == 2L }))
        vm.confirm()
        vm.state.first { it.change == null }
        verify { admin.cancelPayment(2) }
    }

    @Test
    fun `a refused change is reported, and admin mode ending clears the screen`() = runTest {
        every { admin.updateUser(aliceId, any()) } returns refused(403)
        val vm = ready()
        vm.open(aliceId.toString())
        vm.detail()
        vm.ask(UserChange.SetAdmin(true))
        vm.confirm()
        assertTrue(vm.state.first { it.error != null && !it.busy }.change != null)

        fake.state.value = null
        assertEquals(UsersUiState(), vm.state.value)
        assertEquals(Loaded.Idle, vm.content.value)
    }
}
