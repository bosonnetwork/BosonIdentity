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

package io.bosonnetwork.identity.feature.security

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.OAuthReturn
import io.bosonnetwork.identity.core.boson.OAuthReturns
import io.bosonnetwork.identity.core.boson.call
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

enum class LinkStep {
    /** Not open. */
    CLOSED,

    /** Asking the node which account providers it signs in with. */
    LOADING,

    /** Choosing a provider. */
    CHOOSE,

    /** The provider is open in the browser; waiting for the node to send the browser back. */
    WAITING,

    /**
     * Signed in at the provider: linking is confirmed here, with the account passphrase when the
     * account has one, or again after the screen lock was dismissed.
     */
    CONFIRM,

    /** Linking. */
    WORKING,

    /** The account is linked. */
    DONE,
}

data class LinkUiState(
    val identity: IdentityInfo? = null,
    val step: LinkStep = LinkStep.CLOSED,
    val providers: List<ProviderView> = emptyList(),
    /** The provider chosen. */
    val provider: ProviderView? = null,
    /** The account has a passphrase, so linking needs it. */
    val needsPassphrase: Boolean = false,
    val passphrase: String = "",
    val error: String? = null,
    /** A page to open in a browser tab; the screen opens it and calls [LinkAccountViewModel.urlOpened]. */
    val openUrl: String? = null,
    /** Set when the user asked to link before backing up the key; the screen offers the backup. */
    val backupNeeded: Boolean = false,
    /** How many accounts were linked; the screen asks for the account's security again after each. */
    val changes: Int = 0,
)

/**
 * Linking an account at a provider (GitHub, Google, ...) to the identity, so that it signs in to the
 * node's portal as the identity and can reset a forgotten passphrase. The provider opens in a browser
 * tab; the node sends the browser back to this app with a session token, and the identity's key binds
 * that sign-in to the identity: an act of authority, confirmed with the user key and refused until the
 * key's backup is checked. An account with a passphrase also needs it.
 */
@HiltViewModel
class LinkAccountViewModel @Inject constructor(
    private val activeIdentity: ActiveIdentity,
    private val vault: IdentityVault,
    private val nodes: NodeConnections,
    private val oauthReturns: OAuthReturns,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(LinkUiState())
    val state: StateFlow<LinkUiState> = _state.asStateFlow()

    private var job: Job? = null

    // The session token of the sign-in being linked. It stays good after a refusal for want of the
    // passphrase, so linking is tried again with it; a token the node no longer knows takes a new sign-in.
    private var token: String? = null

    init {
        viewModelScope.launch {
            activeIdentity.active.collect { identity ->
                val switched = identity?.userId != _state.value.identity?.userId
                if (switched) {
                    job?.cancel()
                    token = null
                }
                _state.update {
                    if (switched) LinkUiState(identity = identity, changes = it.changes) else it.copy(identity = identity)
                }
            }
        }
    }

    /**
     * Opens linking an account; [passphraseProtected] says whether the account has a passphrase, to ask
     * for it before the screen lock. Without a checked backup of the key, the screen explains instead.
     */
    fun start(passphraseProtected: Boolean) {
        val identity = _state.value.identity ?: return
        val node = identity.homeNode ?: return
        if (!identity.backupVerified) {
            _state.update { it.copy(backupNeeded = true) }
            return
        }
        job?.cancel()
        token = null
        _state.update { LinkUiState(identity = it.identity, step = LinkStep.LOADING, needsPassphrase = passphraseProtected, changes = it.changes) }
        job = viewModelScope.launch {
            try {
                val providers = call { nodes.guest(node).providers.await() }.map { ProviderView(it.id, it.name) }
                _state.update {
                    it.copy(
                        step = LinkStep.CHOOSE,
                        providers = providers,
                        error = if (providers.isEmpty()) context.getString(R.string.sec_link_no_providers) else null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(step = LinkStep.CHOOSE, error = e.userMessage(context)) }
            }
        }
    }

    fun close() {
        job?.cancel()
        token = null
        _state.update { LinkUiState(identity = it.identity, changes = it.changes) }
    }

    fun dismissBackupNeeded() = _state.update { it.copy(backupNeeded = false) }

    fun onPassphraseChange(value: String) = _state.update { it.copy(passphrase = value, error = null) }

    fun urlOpened() = _state.update { it.copy(openUrl = null) }

    /** Back to the providers, from waiting for the browser. */
    fun backToChoose() {
        if (_state.value.step != LinkStep.WAITING && _state.value.step != LinkStep.CONFIRM) return
        job?.cancel()
        token = null
        _state.update { it.copy(step = LinkStep.CHOOSE, passphrase = "", error = null) }
    }

    /**
     * Opens [provider] in a browser tab to sign in there; when the node sends the browser back with the
     * session token, the account is linked (after the passphrase, when the account has one).
     */
    fun choose(provider: ProviderView) {
        val node = _state.value.identity?.homeNode ?: return
        if (_state.value.step != LinkStep.CHOOSE) return
        job?.cancel()
        token = null
        job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val ret = try {
                val url = nodes.guest(node).authorizeUrl(provider.id, OAuthReturns.REDIRECT_URI)
                _state.update { it.copy(step = LinkStep.WAITING, provider = provider, openUrl = url, passphrase = "", error = null) }
                // Started before the browser opens: a return nobody waits for is lost.
                oauthReturns.returns.first { it is OAuthReturn.Token || it is OAuthReturn.Error }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(step = LinkStep.CHOOSE, error = e.userMessage(context)) }
                return@launch
            }
            when (ret) {
                is OAuthReturn.Token -> {
                    token = ret.token
                    if (_state.value.needsPassphrase) {
                        _state.update { it.copy(step = LinkStep.CONFIRM) }
                    } else {
                        bind(null)
                    }
                }
                is OAuthReturn.Error -> _state.update { it.copy(step = LinkStep.CHOOSE, error = context.getString(R.string.sec_provider_error, ret.code)) }
                is OAuthReturn.Grant -> Unit
            }
        }
    }

    /** Links the signed-in account, from the confirmation (with the passphrase, when it is needed). */
    fun link() {
        val s = _state.value
        if (s.step != LinkStep.CONFIRM || token == null) return
        val passphrase = s.passphrase.ifEmpty { null }
        if (s.needsPassphrase && passphrase == null) return
        job = viewModelScope.launch { bind(passphrase) }
    }

    // Binds the sign-in to the identity with its key, which the user confirms with the screen lock.
    private suspend fun bind(passphrase: String?) {
        val identity = _state.value.identity ?: return
        val node = identity.homeNode ?: return
        val token = token ?: return
        if (!identity.backupVerified) return
        val providerName = _state.value.provider?.name.orEmpty()
        _state.update { it.copy(step = LinkStep.WORKING, error = null) }
        try {
            val prompt = AuthPrompt(
                context.getString(R.string.sec_link_prompt_title),
                context.getString(R.string.sec_link_prompt_subtitle, providerName, node.hostName()),
            )
            vault.withUserKey(identity.userId, prompt) { key ->
                val oauth = nodes.oauth(node, token)
                try {
                    call { oauth.bindUserIdentity(key, passphrase).await() }
                } finally {
                    oauth.close()
                }
            }
            this.token = null
            _state.update { it.copy(step = LinkStep.DONE, passphrase = "", changes = it.changes + 1) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.PassphraseRequired) {
            // The sign-in is still good: linking is tried again with it, and the passphrase.
            _state.update {
                it.copy(
                    step = LinkStep.CONFIRM,
                    needsPassphrase = true,
                    passphrase = "",
                    error = if (passphrase != null) e.userMessage(context) else null,
                )
            }
        } catch (e: AppError.Forbidden) {
            val message = if (passphrase != null) context.getString(R.string.sec_wrong_passphrase) else e.userMessage(context)
            _state.update { it.copy(step = LinkStep.CONFIRM, passphrase = "", error = message) }
        } catch (e: AppError.Conflict) {
            this.token = null
            _state.update { it.copy(step = LinkStep.CHOOSE, passphrase = "", error = context.getString(R.string.sec_link_conflict)) }
        } catch (e: AppError.Unauthorized) {
            sessionGone()
        } catch (e: AppError.NotFound) {
            sessionGone()
        } catch (e: Exception) {
            // The screen lock dismissed, the node out of reach, too many tries: the sign-in is kept, to try again.
            _state.update { it.copy(step = LinkStep.CONFIRM, passphrase = "", error = e.userMessage(context)) }
        }
    }

    // The node no longer knows the sign-in (it expired): linking takes a new one.
    private fun sessionGone() {
        token = null
        _state.update { it.copy(step = LinkStep.CHOOSE, passphrase = "", error = context.getString(R.string.sec_link_session_gone)) }
    }
}
