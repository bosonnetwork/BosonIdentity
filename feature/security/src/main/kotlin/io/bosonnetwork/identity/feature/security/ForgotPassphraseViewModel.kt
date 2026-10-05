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
import io.bosonnetwork.Id
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.OAuthReturn
import io.bosonnetwork.identity.core.boson.OAuthReturns
import io.bosonnetwork.identity.core.boson.call
import io.bosonnetwork.identity.core.designsystem.component.PassphraseProblem
import io.bosonnetwork.identity.core.designsystem.component.passphraseProblem
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.vault.AuthPrompt
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

/** An account provider the node signs in with. */
data class ProviderView(val id: String, val name: String)

enum class ForgotStep {
    /** Not open. */
    CLOSED,

    /** Asking the node how the passphrase can be reset. */
    LOADING,

    /** The ways this account has; choosing one. */
    CHOOSE,

    /** Typing a recovery code. */
    CODE,

    /** The provider is open in the browser; waiting for the node to send the browser back. */
    WAITING,

    /** A reset grant is held: choosing the new passphrase. */
    NEW_PASSPHRASE,

    /** The passphrase was reset. */
    DONE,
}

data class ForgotUiState(
    val identity: IdentityInfo? = null,
    val step: ForgotStep = ForgotStep.CLOSED,
    /** What the account can reset its passphrase with; null until the node said. */
    val methods: RecoveryView? = null,
    /** The providers to choose from, when the account has a linked account. */
    val providers: List<ProviderView> = emptyList(),
    val code: String = "",
    val new: String = "",
    val confirm: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** A page to open in a browser tab; the screen opens it and calls [ForgotPassphraseViewModel.urlOpened]. */
    val openUrl: String? = null,
) {
    /** Why the new passphrase would be refused, once something is typed. */
    val newProblem: PassphraseProblem? get() = if (new.isNotEmpty()) passphraseProblem(new) else null
    val mismatch: Boolean get() = confirm.isNotEmpty() && confirm != new
    val canReset: Boolean get() = !busy && passphraseProblem(new) == null && confirm == new

    /**
     * A reset grant is held: it was paid for with a recovery code or a sign-in, and is lost if the
     * dialog closes, so only Cancel closes it.
     */
    val holdsGrant: Boolean get() = step == ForgotStep.NEW_PASSPHRASE
}

/**
 * Resetting a forgotten passphrase, without the old one, in the ways the account has: a recovery code
 * (here, natively), a linked account (its provider in a browser tab, which returns a reset grant to
 * this app), or a passkey (only on the web, where the node's domain is: the node's portal opens). Each
 * gives a one-time reset grant, which sets the new passphrase. None of this uses the identity's key:
 * the key alone can't reset the passphrase, by design.
 */
@HiltViewModel
class ForgotPassphraseViewModel @Inject constructor(
    private val activeIdentity: ActiveIdentity,
    private val nodes: NodeConnections,
    private val oauthReturns: OAuthReturns,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ForgotUiState())
    val state: StateFlow<ForgotUiState> = _state.asStateFlow()

    private var job: Job? = null

    // The reset grant, once a recovery code or a sign-in gave one. It is good once, for a few minutes.
    private var grant: String? = null

    init {
        viewModelScope.launch {
            activeIdentity.active.collect { identity ->
                val switched = identity?.userId != _state.value.identity?.userId
                if (switched) close()
                _state.update { it.copy(identity = identity) }
            }
        }
    }

    /** Opens the dialog: asks the node which ways the account has. */
    fun open() {
        val identity = _state.value.identity ?: return
        if (identity.homeNode == null) return
        job?.cancel()
        grant = null
        _state.update { ForgotUiState(identity = it.identity, step = ForgotStep.LOADING) }
        job = viewModelScope.launch {
            try {
                val client = nodes.deviceClient(identity, unlockPrompt())
                val methods = call { client.recoveryMethods.await() }
                    .let { RecoveryView(it.oauth(), it.passkeys(), it.recoveryCodes()) }
                val providers = if (methods.accounts > 0) {
                    try {
                        val node = checkNotNull(identity.homeNode)
                        call { nodes.guest(node).providers.await() }.map { ProviderView(it.id, it.name) }
                    } catch (e: AppError) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
                _state.update { it.copy(step = ForgotStep.CHOOSE, methods = methods, providers = providers) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(step = ForgotStep.CHOOSE, error = e.userMessage(context)) }
            }
        }
    }

    /** Closes the dialog; a grant held is dropped. */
    fun close() {
        job?.cancel()
        grant = null
        _state.update { ForgotUiState(identity = it.identity) }
    }

    /** Back to the ways to choose from, from typing a code or waiting for the browser. */
    fun backToChoose() {
        if (_state.value.step != ForgotStep.CODE && _state.value.step != ForgotStep.WAITING) return
        job?.cancel()
        _state.update { it.copy(step = ForgotStep.CHOOSE, code = "", busy = false, error = null) }
    }

    fun onCodeChange(value: String) = _state.update { it.copy(code = value, error = null) }
    fun onNewChange(value: String) = _state.update { it.copy(new = value, error = null) }
    fun onConfirmChange(value: String) = _state.update { it.copy(confirm = value, error = null) }

    fun urlOpened() = _state.update { it.copy(openUrl = null) }

    // ---- With a recovery code -----------------------------------------------------------------

    fun useCode() {
        if (_state.value.step != ForgotStep.CHOOSE) return
        _state.update { it.copy(step = ForgotStep.CODE, code = "", error = null) }
    }

    /** Spends the recovery code typed for a reset grant. */
    fun submitCode() {
        val s = _state.value
        val identity = s.identity ?: return
        val node = identity.homeNode ?: return
        val code = s.code.trim()
        if (s.step != ForgotStep.CODE || s.busy || code.isEmpty()) return
        _state.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            try {
                grant = call { nodes.guest(node).redeemRecoveryCode(Id.of(identity.userId), code).await() }
                _state.update { it.copy(step = ForgotStep.NEW_PASSPHRASE, busy = false, code = "", new = "", confirm = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Forbidden) {
                _state.update { it.copy(busy = false, error = context.getString(R.string.sec_forgot_code_invalid)) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.userMessage(context)) }
            }
        }
    }

    // ---- With a linked account ----------------------------------------------------------------

    /**
     * Opens [provider] in a browser tab to reset the passphrase there: it vouches for the account
     * linked to the identity, and the node sends the browser back to this app with a reset grant.
     */
    fun useAccount(provider: ProviderView) {
        val identity = _state.value.identity ?: return
        val node = identity.homeNode ?: return
        if (_state.value.step != ForgotStep.CHOOSE) return
        job?.cancel()
        job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val url = nodes.guest(node).passphraseResetUrl(provider.id, OAuthReturns.REDIRECT_URI)
                _state.update { it.copy(step = ForgotStep.WAITING, openUrl = url, error = null) }
                // Started before the browser opens: a return nobody waits for is lost.
                when (val ret = oauthReturns.returns.first { it is OAuthReturn.Grant || it is OAuthReturn.Error }) {
                    is OAuthReturn.Grant -> {
                        grant = ret.grant
                        _state.update { it.copy(step = ForgotStep.NEW_PASSPHRASE, new = "", confirm = "") }
                    }
                    is OAuthReturn.Error -> _state.update { it.copy(step = ForgotStep.CHOOSE, error = providerError(ret.code)) }
                    is OAuthReturn.Token -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(step = ForgotStep.CHOOSE, error = e.userMessage(context)) }
            }
        }
    }

    // ---- With a passkey -----------------------------------------------------------------------

    /**
     * Opens the node's portal: a passkey works only on the node's own web domain, so a passkey reset is
     * done there ("Forgot passphrase?").
     */
    fun usePasskey() {
        val node = _state.value.identity?.homeNode ?: return
        _state.update { it.copy(openUrl = node.url.trimEnd('/') + "/portal/") }
    }

    // ---- The new passphrase ------------------------------------------------------------------

    /** Sets the new passphrase with the grant held, which is used up. */
    fun reset() {
        val s = _state.value
        val node = s.identity?.homeNode ?: return
        val grant = grant ?: return
        if (s.step != ForgotStep.NEW_PASSPHRASE || !s.canReset) return
        val new = s.new
        _state.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            try {
                call { nodes.guest(node).resetPassphrase(grant, new).await() }
                this@ForgotPassphraseViewModel.grant = null
                _state.update { it.copy(step = ForgotStep.DONE, busy = false, new = "", confirm = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Unauthorized) {
                // The grant expired or was used: it takes another code or sign-in.
                this@ForgotPassphraseViewModel.grant = null
                _state.update {
                    it.copy(step = ForgotStep.CHOOSE, busy = false, new = "", confirm = "", error = context.getString(R.string.sec_forgot_grant_expired))
                }
            } catch (e: Exception) {
                // The grant stays good: a weak passphrase or a network failure can be tried again.
                _state.update { it.copy(busy = false, error = e.userMessage(context)) }
            }
        }
    }

    private fun providerError(code: String): String =
        if (code == "reset_failed") {
            context.getString(R.string.sec_forgot_account_refused)
        } else {
            context.getString(R.string.sec_provider_error, code)
        }

    private fun unlockPrompt() = AuthPrompt(
        context.getString(R.string.sec_unlock_prompt_title),
        context.getString(R.string.sec_unlock_prompt_subtitle),
    )
}
