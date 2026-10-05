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

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.Id
import io.bosonnetwork.director.client.BlacklistedNode
import io.bosonnetwork.director.client.Device
import io.bosonnetwork.director.client.DirectorAdmin
import io.bosonnetwork.director.client.Feature
import io.bosonnetwork.director.client.FederatedNode
import io.bosonnetwork.director.client.FederationProposal
import io.bosonnetwork.director.client.Payment
import io.bosonnetwork.director.client.Plan
import io.bosonnetwork.director.client.Profile
import io.bosonnetwork.director.client.Subscription
import io.bosonnetwork.director.client.exceptions.DirectorException
import io.bosonnetwork.identity.core.boson.AdminSession
import io.bosonnetwork.identity.core.boson.AdminState
import io.bosonnetwork.identity.core.boson.call
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.web.PaginatedResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * A stand-in for admin mode: its [state] is set by the test, and calls go to [admin], a mocked admin
 * client, with failures mapped as the real session maps them. [end] closes it as a 401 would.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeAdmin {
    val node = NodeAddress("https://node.example", Id.random().toString())
    val adminId: String = Id.random().toString()
    val admin = mockk<DirectorAdmin>()
    val state = MutableStateFlow<AdminState?>(null)
    val session = mockk<AdminSession> {
        every { state } returns this@FakeAdmin.state
        coEvery { withClient<Any?>(any()) } coAnswers {
            if (this@FakeAdmin.state.value == null) throw io.bosonnetwork.identity.core.model.AppError.Unauthorized("ended")
            call { firstArg<suspend (DirectorAdmin) -> Any?>().invoke(admin) }
        }
        coEvery { end() } coAnswers { this@FakeAdmin.state.value = null }
    }
    val context = mockk<Context>(relaxed = true)
    private val viewModels = mutableListOf<ViewModel>()

    fun open() {
        state.value = AdminState(adminId, node, Long.MAX_VALUE)
    }

    fun <T : ViewModel> track(viewModel: T): T = viewModel.also { viewModels += it }

    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
    }
}

// The client's models: mocked, with the getters the app reads.

fun <T> done(value: T): CompletableFuture<T> = CompletableFuture.completedFuture(value)

fun ok(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

/** A call the node refused with [status]. */
fun <T> refused(status: Int): CompletableFuture<T> =
    CompletableFuture.failedFuture(DirectorException.fromResponse(status, "refused", null))

fun <T : Any> page(items: List<T>, page: Long = 1, pageSize: Long = USERS_PAGE, total: Long = items.size.toLong()): PaginatedResult<T> =
    PaginatedResult.of(page, pageSize, total, items)

fun user(id: Id = Id.random(), name: String? = null, email: String? = null, admin: Boolean = false, createdAt: Long = 1_000): Profile =
    mockk {
        every { this@mockk.id } returns id
        every { this@mockk.name } returns Optional.ofNullable(name)
        every { this@mockk.email } returns Optional.ofNullable(email)
        every { bio } returns Optional.empty()
        every { isAdmin } returns admin
        every { planName } returns "Free"
        every { isPassphraseProtected } returns false
        every { this@mockk.createdAt } returns createdAt
    }

fun device(id: Id = Id.random(), name: String = "Phone", lastSeen: Long = 0): Device = mockk {
    every { this@mockk.id } returns id
    every { this@mockk.name } returns name
    every { app } returns "Photon"
    every { kind } returns Device.KIND_APP
    every { this@mockk.lastSeen } returns lastSeen
}

fun subscription(id: Long = 1, status: Subscription.Status = Subscription.Status.ACTIVE): Subscription = mockk {
    every { this@mockk.id } returns id
    every { planName } returns Optional.of("Pro")
    every { this@mockk.status } returns status
    every { startDate } returns 1_000
    every { endDate } returns 2_000
}

fun payment(id: Long = 1, status: Payment.Status = Payment.Status.PENDING, transaction: Boolean = true): Payment = mockk {
    every { this@mockk.id } returns id
    every { amount } returns BigDecimal("9.90")
    every { currency } returns "USD"
    every { intent } returns Payment.Intent.SUBSCRIPTION
    every { this@mockk.status } returns status
    every { createdAt } returns 1_000
    every { network } returns Optional.ofNullable(if (transaction) "tron" else null)
    every { token } returns Optional.ofNullable(if (transaction) "USDT" else null)
    every { tokenAmount } returns Optional.ofNullable(if (transaction) BigDecimal("9.90") else null)
    every { walletFrom } returns Optional.ofNullable(if (transaction) "Tfrom" else null)
    every { walletTo } returns Optional.ofNullable(if (transaction) "Tto" else null)
    every { transactionId } returns Optional.ofNullable(if (transaction) "tx1" else null)
}

fun plan(id: Int, name: String, active: Boolean = true, price: String = "10", description: String? = null): Plan = mockk {
    every { this@mockk.id } returns id
    every { this@mockk.name } returns name
    every { this@mockk.description } returns Optional.ofNullable(description)
    every { this@mockk.price } returns BigDecimal(price)
    every { currency } returns "USD"
    every { cycle } returns Plan.Cycle.MONTHLY
    every { isFree } returns (BigDecimal(price).signum() == 0)
    every { isActive } returns active
}

fun feature(id: Int, planId: Int, serviceId: String): Feature = mockk {
    every { this@mockk.id } returns id
    every { this@mockk.planId } returns planId
    every { this@mockk.serviceId } returns serviceId
    every { this@mockk.feature } returns mapOf("quota" to 100)
}

fun federatedNode(id: Id = Id.random(), name: String? = null, federated: Boolean = true): FederatedNode = mockk {
    every { this@mockk.id } returns id
    every { this@mockk.name } returns Optional.ofNullable(name)
    every { apiEndpoint } returns Optional.of("https://other.example")
    every { software } returns Optional.of("boson")
    every { version } returns Optional.of("3.2")
    every { isFederated } returns federated
    every { reputation } returns 10
    every { updatedAt } returns 1_000
}

fun proposal(id: Long, nodeId: Id = Id.random(), proposedAt: Long = 1_000): FederationProposal = mockk {
    every { this@mockk.id } returns id
    every { this@mockk.nodeId } returns nodeId
    every { name } returns Optional.empty()
    every { role } returns FederationProposal.Role.OFFER
    every { status } returns FederationProposal.Status.PROPOSED
    every { this@mockk.proposedAt } returns proposedAt
}

fun blacklisted(id: Long, nodeId: Id? = null, host: String? = null, createdAt: Long = 1_000, reason: String? = null): BlacklistedNode = mockk {
    every { this@mockk.id } returns id
    every { this@mockk.nodeId } returns Optional.ofNullable(nodeId)
    every { nodeHost } returns Optional.ofNullable(host)
    every { isAuto } returns false
    every { this@mockk.reason } returns Optional.ofNullable(reason)
    every { this@mockk.createdAt } returns createdAt
}

/** What an update object would send: the client keeps its fields to itself. */
fun Any.sent(): Map<*, *> =
    this::class.java.getDeclaredMethod("fields").apply { isAccessible = true }.invoke(this) as Map<*, *>
