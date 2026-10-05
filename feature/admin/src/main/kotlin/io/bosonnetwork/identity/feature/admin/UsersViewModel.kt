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
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.Id
import io.bosonnetwork.director.client.Device
import io.bosonnetwork.director.client.DirectorAdmin
import io.bosonnetwork.director.client.Payment
import io.bosonnetwork.director.client.PaymentUpdate
import io.bosonnetwork.director.client.Profile
import io.bosonnetwork.director.client.Sort
import io.bosonnetwork.director.client.Subscription
import io.bosonnetwork.director.client.UserUpdate
import io.bosonnetwork.identity.core.boson.AdminSession
import io.bosonnetwork.identity.core.model.AppError
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

/** A user account, as the node lists it. */
data class UserView(
    val id: String,
    val name: String?,
    val email: String?,
    val bio: String?,
    val admin: Boolean,
    val plan: String,
    val passphraseProtected: Boolean,
    val createdAt: Long,
    /** The administrator using the app: they can't remove themselves, nor take their own admin flag. */
    val you: Boolean,
) {
    /** Whether [query] matches its name, email or id, ignoring case. */
    fun matches(query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || listOfNotNull(name, email, id).any { it.contains(q, ignoreCase = true) }
    }
}

/** The pages of users read so far. */
data class UserPages(val users: List<UserView>, val page: Long, val totalPages: Long, val total: Long) {
    val more: Boolean get() = page < totalPages
}

/** A device of a user. */
data class UserDevice(val id: String, val name: String, val app: String, val passkey: Boolean, val lastSeen: Long)

data class SubscriptionView(val id: Long, val plan: String?, val status: Subscription.Status, val startDate: Long, val endDate: Long)

/** A payment, with the settling transaction a confirmation repeats to the node. */
data class PaymentView(
    val id: Long,
    val amount: BigDecimal,
    val currency: String,
    val intent: Payment.Intent,
    val status: Payment.Status,
    val createdAt: Long,
    val network: String?,
    val token: String?,
    val tokenAmount: BigDecimal?,
    val walletFrom: String?,
    val walletTo: String?,
    val transactionId: String?,
) {
    /**
     * A pending payment can be confirmed when the user submitted its transaction: the node wants it
     * again with the confirmation. One without it is settled on the web.
     */
    val canConfirm: Boolean
        get() = status == Payment.Status.PENDING &&
            network != null && token != null && tokenAmount != null && walletFrom != null && transactionId != null

    /** Only a payment that hasn't ended can be cancelled. */
    val canCancel: Boolean get() = status == Payment.Status.UNPAID || status == Payment.Status.PENDING
}

data class UserDetail(
    val user: UserView,
    val devices: List<UserDevice>,
    val subscriptions: List<SubscriptionView>,
    val payments: List<PaymentView>,
)

/** A change to a user waiting for the administrator's confirmation. */
sealed interface UserChange {
    data class SetAdmin(val admin: Boolean) : UserChange
    data class RemoveDevice(val device: UserDevice) : UserChange
    data class ConfirmPayment(val payment: PaymentView) : UserChange
    data class CancelPayment(val payment: PaymentView) : UserChange

    /** Removing the user: confirmed by typing the first characters of their id. */
    data object Remove : UserChange
}

data class UsersUiState(
    val query: String = "",
    /** A user found by the full id typed in the search, when not among the pages read. */
    val found: UserView? = null,
    val loadingMore: Boolean = false,
    val moreError: String? = null,
    /** The user whose detail is open. */
    val selected: String? = null,
    val detail: Loaded<UserDetail> = Loaded.Idle,
    /** Set while a change waits for confirmation. */
    val change: UserChange? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** The page size of the user list. */
const val USERS_PAGE = 50L

/**
 * Users: the accounts on the node, newest first, a page at a time; a user's detail with their devices,
 * subscriptions and payments; making them an administrator or not, removing a device, confirming or
 * cancelling a payment, and removing the user. The node has no search, so the search filters the pages
 * read; a full user id is looked up directly.
 */
@HiltViewModel
class UsersViewModel @Inject constructor(
    session: AdminSession,
    @ApplicationContext context: Context,
) : AdminSectionViewModel<UserPages>(session, context) {

    private val _state = MutableStateFlow(UsersUiState())
    val state: StateFlow<UsersUiState> = _state.asStateFlow()

    private var detailJob: Job? = null
    private var lookup: Job? = null

    init {
        watchSession()
    }

    override suspend fun load(admin: DirectorAdmin): UserPages {
        val page = admin.listUsers(1, USERS_PAGE, Sort.desc("createdAt")).await()
        return UserPages(page.items().map { it.toView() }, page.page(), page.totalPages(), page.totalItems())
    }

    override fun onSessionEnded() {
        detailJob?.cancel()
        lookup?.cancel()
        _state.value = UsersUiState()
    }

    /** Reads the next page. */
    fun loadMore() {
        val pages = (content.value as? Loaded.Ready)?.value ?: return
        if (!pages.more || _state.value.loadingMore) return
        _state.update { it.copy(loadingMore = true, moreError = null) }
        viewModelScope.launch {
            try {
                val next = session.withClient { it.listUsers(pages.page + 1, USERS_PAGE, Sort.desc("createdAt")).await() }
                changeContent { current ->
                    val known = current.users.map { it.id }.toSet()
                    UserPages(
                        current.users + next.items().map { it.toView() }.filter { it.id !in known },
                        next.page(),
                        next.totalPages(),
                        next.totalItems(),
                    )
                }
                _state.update { it.copy(loadingMore = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, moreError = failure(e)) }
            }
        }
    }

    /** Filters the list; a full user id not among the pages read is asked for. */
    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query, found = null) }
        lookup?.cancel()
        val id = query.trim().takeIf { it.length >= 40 }?.let { runCatching { Id.of(it) }.getOrNull() } ?: return
        val pages = (content.value as? Loaded.Ready)?.value
        if (pages?.users?.any { it.id == id.toString() } == true) return
        lookup = viewModelScope.launch {
            try {
                val user = session.withClient { it.getUser(id).await() }.orElse(null)?.toView()
                _state.update { if (it.query == query) it.copy(found = user) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Searching is best effort; the list stays as it is.
            }
        }
    }

    /** The users the list shows, for the query typed. */
    fun visible(pages: UserPages, state: UsersUiState): List<UserView> =
        listOfNotNull(state.found) + pages.users.filter { it.matches(state.query) }

    /** Opens a user's detail. */
    fun open(userId: String) {
        _state.update { it.copy(selected = userId, detail = Loaded.Loading, change = null, error = null) }
        loadDetail(userId)
    }

    /** Back to the list. */
    fun close() {
        detailJob?.cancel()
        _state.update { it.copy(selected = null, detail = Loaded.Idle, change = null, error = null) }
    }

    /** Asks for the open user's detail again. */
    fun refreshDetail() {
        val userId = _state.value.selected ?: return
        loadDetail(userId)
    }

    private fun loadDetail(userId: String) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            val detail = try {
                Loaded.Ready(session.withClient { detail(it, Id.of(userId)) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.NotFound) {
                Loaded.Failed(context.getString(R.string.adm_user_gone))
            } catch (e: Exception) {
                Loaded.Failed(failure(e))
            }
            _state.update { if (it.selected == userId) it.copy(detail = detail) else it }
        }
    }

    private suspend fun detail(admin: DirectorAdmin, userId: Id): UserDetail = coroutineScope {
        val devices = async { admin.listDevices(userId).await() }
        val subscriptions = async { admin.listSubscriptions(userId).await().items() }
        val payments = async { admin.listPayments(userId).await().items() }
        val user = admin.getUser(userId).await().orElseThrow { AppError.NotFound("No such user") }
        UserDetail(
            user = user.toView(),
            devices = devices.await().map { it.toView() }.sortedByDescending { it.lastSeen },
            subscriptions = subscriptions.await().map { it.toView() }.sortedByDescending { it.startDate },
            payments = payments.await().map { it.toView() }.sortedByDescending { it.createdAt },
        )
    }

    /** Asks to make [change]; the dialog then confirms it. */
    fun ask(change: UserChange) {
        val detail = (_state.value.detail as? Loaded.Ready)?.value ?: return
        // An administrator can't take their own power away here, nor remove themselves.
        if (detail.user.you && (change == UserChange.Remove || change == UserChange.SetAdmin(false))) return
        _state.update { it.copy(change = change, error = null) }
    }

    fun cancel() = _state.update { if (it.busy) it else it.copy(change = null, error = null) }

    /**
     * Makes the change asked for. Removing the user needs [typed] to match the first characters of
     * their id.
     */
    fun confirm(typed: String = "") {
        val s = _state.value
        val change = s.change ?: return
        val detail = (s.detail as? Loaded.Ready)?.value ?: return
        if (s.busy) return
        val user = detail.user
        if (change == UserChange.Remove && !confirms(typed, confirmCode(user.id))) return
        val userId = Id.of(user.id)
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                session.withClient { admin ->
                    when (change) {
                        is UserChange.SetAdmin -> admin.updateUser(userId, UserUpdate().admin(change.admin)).await()
                        is UserChange.RemoveDevice -> admin.removeDevice(Id.of(change.device.id)).await()
                        is UserChange.ConfirmPayment -> admin.updatePayment(change.payment.id, change.payment.confirmation()).await()
                        is UserChange.CancelPayment -> admin.cancelPayment(change.payment.id).await()
                        UserChange.Remove -> admin.removeUser(userId).await()
                    }
                }
                if (change == UserChange.Remove) {
                    _state.update { it.copy(busy = false, change = null, selected = null, detail = Loaded.Idle) }
                    changeContent { pages -> pages.copy(users = pages.users.filter { it.id != user.id }, total = (pages.total - 1).coerceAtLeast(0)) }
                    _state.update { it.copy(found = it.found?.takeIf { found -> found.id != user.id }) }
                } else {
                    _state.update { it.copy(busy = false, change = null) }
                    if (change is UserChange.SetAdmin) {
                        changeContent { pages -> pages.copy(users = pages.users.map { if (it.id == user.id) it.copy(admin = change.admin) else it }) }
                    }
                    loadDetail(user.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = failure(e)) }
            }
        }
    }

    private fun Profile.toView() = UserView(
        id = id.toString(),
        name = name.orElse(null)?.takeIf { it.isNotBlank() },
        email = email.orElse(null)?.takeIf { it.isNotBlank() },
        bio = bio.orElse(null)?.takeIf { it.isNotBlank() },
        admin = isAdmin,
        plan = planName,
        passphraseProtected = isPassphraseProtected,
        createdAt = createdAt,
        you = id.toString() == session.state.value?.userId,
    )
}

private fun Device.toView() = UserDevice(
    id = id.toString(),
    name = name,
    app = app,
    passkey = kind == Device.KIND_PASSKEY,
    lastSeen = lastSeen,
)

private fun Subscription.toView() = SubscriptionView(id, planName.orElse(null), status, startDate, endDate)

private fun Payment.toView() = PaymentView(
    id = id,
    amount = amount,
    currency = currency,
    intent = intent,
    status = status,
    createdAt = createdAt,
    network = network.orElse(null),
    token = token.orElse(null),
    tokenAmount = tokenAmount.orElse(null),
    walletFrom = walletFrom.orElse(null),
    walletTo = walletTo.orElse(null),
    transactionId = transactionId.orElse(null),
)

/** Confirming a payment repeats the transaction the user submitted, as the node requires. */
internal fun PaymentView.confirmation(): PaymentUpdate =
    PaymentUpdate(Payment.Status.CONFIRMED)
        .network(requireNotNull(network))
        .token(requireNotNull(token))
        .tokenAmount(requireNotNull(tokenAmount))
        .walletFrom(requireNotNull(walletFrom))
        .transactionId(requireNotNull(transactionId))
        .apply { walletTo?.let { walletTo(it) } }
