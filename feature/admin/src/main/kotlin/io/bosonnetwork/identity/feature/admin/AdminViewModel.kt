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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.AdminSession
import io.bosonnetwork.identity.core.boson.AdminState
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdminUiState(
    val identity: IdentityInfo? = null,
    /** The admin session of this identity at its home node; null when admin mode is closed. */
    val session: AdminState? = null,
    /** Opening admin mode: the prompt is up, or the node is being asked. */
    val starting: Boolean = false,
    /** Why admin mode didn't open. */
    val error: String? = null,
)

/**
 * Admin mode for the identity in use at its home node: opening it (one prompt, never on its own when
 * the tab opens), the time left, and ending it. Only the home node for now; other nodes an identity
 * administers can be added later, with a node picker.
 */
@HiltViewModel
class AdminViewModel @Inject constructor(
    private val activeIdentity: ActiveIdentity,
    private val vault: IdentityVault,
    private val session: AdminSession,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(AdminUiState())
    val state: StateFlow<AdminUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(activeIdentity.active, session.state) { identity, open -> identity to open }.collect { (identity, open) ->
                val switched = identity?.userId != _state.value.identity?.userId
                // A session belongs to the identity and node it was opened for; switching away ends it.
                val ours = open?.takeIf { identity != null && it.userId == identity.userId && it.node == identity.homeNode }
                if (open != null && ours == null) session.end()
                _state.update {
                    it.copy(identity = identity, session = ours, error = if (switched) null else it.error)
                }
            }
        }
    }

    /** Opens admin mode, after the user confirms with their screen lock. */
    fun start() {
        val s = _state.value
        val identity = s.identity ?: return
        val node = identity.homeNode ?: return
        if (s.starting || s.session != null) return
        if (!identity.backupVerified) {
            _state.update { it.copy(error = context.getString(R.string.adm_backup_first)) }
            return
        }
        _state.update { it.copy(starting = true, error = null) }
        viewModelScope.launch {
            try {
                val prompt = AuthPrompt(
                    context.getString(R.string.adm_prompt_title),
                    context.getString(R.string.adm_prompt_subtitle, node.host()),
                )
                session.start(identity, node, prompt)
                if (!identity.administers) vault.update(identity.userId) { it.copy(administers = true) }
                _state.update { it.copy(starting = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.AuthenticationCancelled) {
                _state.update { it.copy(starting = false) }
            } catch (e: Exception) {
                val refused = e is AppError.Unauthorized || e is AppError.Forbidden
                val message = if (refused) context.getString(R.string.adm_not_admin, node.host()) else e.userMessage(context)
                _state.update { it.copy(starting = false, error = message) }
            }
        }
    }

    /** "End now". */
    fun end() {
        viewModelScope.launch { session.end() }
    }

    /** The countdown reached zero: ends the session if its time is really up. */
    fun expired() {
        viewModelScope.launch { session.endIfExpired() }
    }
}
