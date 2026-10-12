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

package io.bosonnetwork.identity

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.Id
import io.bosonnetwork.director.client.SuperNodeCode
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.AvatarStore
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.vault.IdentityVault
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An app's request, as [AppRequestActivity] read it from the intent. */
sealed interface AppRequest {
    /** The app that asked: its name as the phone shows it, and its package. */
    val requesterLabel: String
    val requesterPackage: String

    /** The identity the app named, if any. */
    val userId: String?

    data class HomeNode(
        override val requesterLabel: String,
        override val requesterPackage: String,
        override val userId: String?,
    ) : AppRequest

    data class ApproveDevice(
        override val requesterLabel: String,
        override val requesterPackage: String,
        override val userId: String?,
        /** The pairing code, as the app gave it. */
        val code: String,
    ) : AppRequest
}

data class AppRequestState(
    val loading: Boolean = true,
    val identities: List<IdentityInfo> = emptyList(),
    /** The identity chosen; only one with a home node can be. */
    val selected: String? = null,
    /**
     * For an approval, the identity it is for without asking: the one the app named if it is here, or
     * the only one. Null when the user picks.
     */
    val direct: String? = null,
    val working: Boolean = false,
    val error: String? = null,
) {
    /** Why the request can't be answered as the identities stand, as an `AppRequests.ERROR_` value. */
    val unanswerable: String?
        get() = when {
            loading -> null
            identities.isEmpty() -> AppRequests.ERROR_NO_IDENTITY
            identities.none { it.homeNode != null } -> AppRequests.ERROR_NO_HOME_NODE
            else -> null
        }
}

/** The identities an app's request may be answered for, and sharing one's home node. */
@HiltViewModel
class AppRequestViewModel @Inject constructor(
    private val vault: IdentityVault,
    private val activeIdentity: ActiveIdentity,
    private val nodes: NodeConnections,
    private val avatarStore: AvatarStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(AppRequestState())
    val state: StateFlow<AppRequestState> = _state.asStateFlow()

    val avatars = avatarStore.avatars

    private var started = false

    fun start(request: AppRequest) {
        if (started) return
        started = true
        viewModelScope.launch {
            vault.load()
            avatarStore.load()
            val identities = vault.identities.value
            val active = activeIdentity.active.first()?.userId
            _state.update {
                it.copy(
                    loading = false,
                    identities = identities,
                    selected = propose(identities, request.userId, active),
                    direct = if (request is AppRequest.ApproveDevice) direct(identities, request.userId) else null,
                )
            }
        }
    }

    fun select(userId: String) {
        val identity = _state.value.identities.firstOrNull { it.userId == userId } ?: return
        if (identity.homeNode != null) _state.update { it.copy(selected = userId, error = null) }
    }

    /**
     * The chosen identity's home node as its code, `boson:supernode:1:...`. The node's id pins it for the
     * app: one the identity knows only by its address is asked for it first.
     */
    fun shareHomeNode(onShared: (userId: String, code: String) -> Unit) {
        val s = _state.value
        val identity = s.identities.firstOrNull { it.userId == s.selected } ?: return
        val node = identity.homeNode ?: return
        if (s.working) return
        _state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            try {
                val nodeId = node.nodeId ?: nodes.identify(node).nodeId ?: throw IllegalStateException("The node reports no id")
                val code = SuperNodeCode.of(Id.of(nodeId), node.url).toString()
                _state.update { it.copy(working = false) }
                onShared(identity.userId, code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(working = false, error = e.userMessage(context)) }
            }
        }
    }

    companion object {
        /** The identity proposed: the one asked for, else the one in use, else the first; one with a home node. */
        fun propose(identities: List<IdentityInfo>, asked: String?, active: String?): String? =
            listOf(asked, active).firstNotNullOfOrNull { id -> identities.firstOrNull { it.userId == id && it.homeNode != null } }?.userId
                ?: identities.firstOrNull { it.homeNode != null }?.userId

        /** The identity an approval is for without asking: the one asked for if here, else the only one; with a home node. */
        fun direct(identities: List<IdentityInfo>, asked: String?): String? {
            val identity = identities.firstOrNull { it.userId == asked } ?: identities.singleOrNull()
            return identity?.takeIf { it.homeNode != null }?.userId
        }
    }
}
