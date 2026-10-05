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

package io.bosonnetwork.identity.feature.identity

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.AdminAccess
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.call
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

/** What the home node says about the identity. */
data class NodeView(
    val name: String?,
    val admin: Boolean,
    val passphraseProtected: Boolean,
    val plan: String?,
    /** Ways to reset a forgotten passphrase; null if the node did not say. */
    val recoveryCodes: Int?,
    val recoveryMethods: Int?,
)

sealed interface NodeState {
    /** No home node yet. */
    data object None : NodeState
    data object Loading : NodeState

    /** The user dismissed the unlock prompt. */
    data object Locked : NodeState
    data class Ready(val view: NodeView) : NodeState
    data class Failed(val message: String) : NodeState
}

data class IdentityUiState(
    val identity: IdentityInfo? = null,
    val node: NodeState = NodeState.None,
)

/**
 * The home screen: the identity and how well it is protected. The node is asked through this app's
 * device key; opening it is what unlocks the app, when the phone was not unlocked recently.
 */
@HiltViewModel
class IdentityViewModel @Inject constructor(
    private val vault: IdentityVault,
    private val activeIdentity: ActiveIdentity,
    private val nodes: NodeConnections,
    private val adminAccess: AdminAccess,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(IdentityUiState())
    val state: StateFlow<IdentityUiState> = _state.asStateFlow()

    private var loading: Job? = null

    init {
        viewModelScope.launch {
            // The identity picked in the switcher. A change of its name alone doesn't ask the node again.
            activeIdentity.active.collect { identity ->
                val previous = _state.value.identity
                val changed = identity?.userId != previous?.userId || identity?.homeNode != previous?.homeNode
                _state.update { it.copy(identity = identity) }
                if (changed || _state.value.node is NodeState.None) refresh()
            }
        }
    }

    /** Asks the home node again; after a dismissed prompt, this is the "Unlock" button. */
    fun refresh() {
        val identity = _state.value.identity ?: return
        // An answer still on its way may be for the identity shown before.
        loading?.cancel()
        if (identity.homeNode == null) {
            _state.update { it.copy(node = NodeState.None) }
            return
        }
        _state.update { it.copy(node = NodeState.Loading) }
        loading = viewModelScope.launch {
            _state.update { it.copy(node = load(identity)) }
        }
    }

    private suspend fun load(identity: IdentityInfo): NodeState =
        try {
            val prompt = AuthPrompt(
                context.getString(R.string.id_unlock_prompt_title),
                context.getString(R.string.id_unlock_prompt_subtitle),
            )
            val client = nodes.deviceClient(identity, prompt)
            val profile = call { client.profile.await() }
            val name = profile.name.orElse(null)?.takeIf { it.isNotBlank() }
            // The switcher shows the identity by the name it has at its node.
            if (name != identity.name) vault.update(identity.userId) { it.copy(name = name) }
            // An account marked as an administrator gets the Admin tab, with no prompt of its own.
            adminAccess.reportProfile(identity.userId, profile.isAdmin)
            // Older nodes don't report recovery methods; the screen then just doesn't show them.
            val recovery = try {
                call { client.recoveryMethods.await() }
            } catch (e: AppError) {
                null
            }
            NodeState.Ready(
                NodeView(
                    name = name,
                    admin = profile.isAdmin,
                    passphraseProtected = profile.isPassphraseProtected,
                    plan = profile.planName,
                    recoveryCodes = recovery?.recoveryCodes(),
                    recoveryMethods = recovery?.let { it.oauth() + it.passkeys() + it.recoveryCodes() },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.AuthenticationCancelled) {
            NodeState.Locked
        } catch (e: Exception) {
            NodeState.Failed(e.userMessage(context))
        }
}
