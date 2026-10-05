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

package io.bosonnetwork.identity.feature.devices

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.director.client.SignInRequest
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

enum class SignInStep {
    /** Reading the request the code is for. */
    LOADING,

    /** What the page signs in to, from where; pick its number, approve or refuse. */
    REVIEW,

    /** Approving or refusing. */
    WORKING,

    /** The page is signed in. */
    DONE,

    /** The sign-in was refused: by the user, or by a wrong number. */
    REFUSED,

    /** There is no such request any more: it expired, or it was already answered. */
    EXPIRED,

    /** The request couldn't be read. */
    ERROR,
}

data class SignInUiState(
    val identity: IdentityInfo? = null,
    val step: SignInStep = SignInStep.LOADING,
    /** [SignInRequest.APP_PORTAL] or [SignInRequest.APP_ADMIN]. */
    val app: String = SignInRequest.APP_PORTAL,
    val requestedFrom: String? = null,
    /** The numbers to choose from; one of them is the page's. */
    val choices: List<Int> = emptyList(),
    /** The number the user picked, if any. */
    val picked: Int? = null,
    /** Seconds left before the request expires, counted on this phone. */
    val remainingSeconds: Long = 0,
    val expired: Boolean = false,
    /** The refusal came from a wrong number, not from the user. */
    val wrongNumber: Boolean = false,
    val error: String? = null,
) {
    /** Approving is allowed: the backup is checked, the request is alive, and a number was picked. */
    val canApprove: Boolean
        get() = step == SignInStep.REVIEW && !expired && picked != null && identity?.backupVerified == true
}

/**
 * Signing a web page in ("Sign in with Boson Identity"), from the code the page shows. Reading and
 * refusing go through this app's device key; approving is an act of authority, confirmed with the user
 * key every time. The user must pick the number the page shows: approving out of habit picks wrong,
 * and a wrong number refuses the sign-in.
 */
@HiltViewModel
class ApproveSignInViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val vault: IdentityVault,
    private val nodes: NodeConnections,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val userId: String = checkNotNull(savedState[ApproveDeviceViewModel.ARG_USER_ID]) { "No identity to sign in with" }
    private val requestId: String = checkNotNull(savedState[ARG_REQUEST_ID]) { "No sign-in request" }

    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    /** This phone's clock for the countdown, in milliseconds; monotonic. */
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    private var job: Job? = null
    private var ticker: Job? = null

    init {
        viewModelScope.launch {
            vault.load()
            vault.identities.collect { list -> _state.update { it.copy(identity = list.firstOrNull { i -> i.userId == userId }) } }
        }
        load()
    }

    fun onPick(number: Int) = _state.update { it.copy(picked = number) }

    /** Reads the request again, after it couldn't be read. */
    fun load() {
        job?.cancel()
        _state.update { it.copy(step = SignInStep.LOADING, error = null) }
        job = viewModelScope.launch {
            try {
                val identity = _state.mapNotNull { it.identity }.first()
                val client = nodes.deviceClient(identity, unlockPrompt())
                val request = call { client.getSignInRequest(requestId).await() }.orElse(null)
                val fetchedAt = clock()
                if (request == null) {
                    _state.update { it.copy(step = SignInStep.EXPIRED) }
                    return@launch
                }
                _state.update {
                    it.copy(
                        step = SignInStep.REVIEW,
                        app = request.app,
                        requestedFrom = request.requestedFrom.orElse(null),
                        choices = request.choices,
                    )
                }
                countDown(request.expiresAt - request.createdAt, fetchedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(step = SignInStep.ERROR, error = e.userMessage(context)) }
            }
        }
    }

    /** Signs the page in with the picked number, after the user confirms with their screen lock. */
    fun approve() {
        val s = _state.value
        if (!s.canApprove) return
        val identity = s.identity ?: return
        val node = identity.homeNode ?: return
        val number = s.picked ?: return
        _state.update { it.copy(step = SignInStep.WORKING, error = null) }
        job = viewModelScope.launch {
            try {
                val what = context.getString(if (s.app == SignInRequest.APP_ADMIN) R.string.dev_signin_admin else R.string.dev_signin_portal)
                val prompt = AuthPrompt(
                    context.getString(R.string.dev_signin_prompt_title),
                    context.getString(R.string.dev_signin_prompt_subtitle, what, node.hostName()),
                )
                nodes.asUser(identity.userId, node, prompt) { client, _ ->
                    call { client.approveSignInRequest(requestId, number).await() }
                }
                ticker?.cancel()
                _state.update { it.copy(step = SignInStep.DONE) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Conflict) {
                // Not the page's number: the node refused the sign-in.
                ticker?.cancel()
                _state.update { it.copy(step = SignInStep.REFUSED, wrongNumber = true) }
            } catch (e: AppError.NotFound) {
                ticker?.cancel()
                _state.update { it.copy(step = SignInStep.EXPIRED) }
            } catch (e: AppError.Forbidden) {
                _state.update {
                    it.copy(step = SignInStep.REVIEW, error = context.getString(R.string.dev_signin_admin_refused))
                }
            } catch (e: Exception) {
                _state.update { it.copy(step = SignInStep.REVIEW, error = e.userMessage(context)) }
            }
        }
    }

    /** Refuses the sign-in. Refusing grants nothing, so this phone's key is enough. */
    fun refuse() {
        val identity = _state.value.identity ?: return
        _state.update { it.copy(step = SignInStep.WORKING, error = null) }
        job = viewModelScope.launch {
            try {
                val client = nodes.deviceClient(identity, unlockPrompt())
                call { client.denySignInRequest(requestId).await() }
                ticker?.cancel()
                _state.update { it.copy(step = SignInStep.REFUSED) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.NotFound) {
                _state.update { it.copy(step = SignInStep.EXPIRED) }
            } catch (e: Exception) {
                _state.update { it.copy(step = SignInStep.REVIEW, error = e.userMessage(context)) }
            }
        }
    }

    private fun countDown(lifetime: Long, fetchedAt: Long) {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                val left = lifetime - (clock() - fetchedAt)
                if (left <= 0) {
                    _state.update { it.copy(remainingSeconds = 0, expired = true) }
                    return@launch
                }
                _state.update { it.copy(remainingSeconds = (left + 999) / 1000) }
                delay(minOf(1000L, left))
            }
        }
    }

    private fun unlockPrompt() = AuthPrompt(
        context.getString(R.string.dev_unlock_prompt_title),
        context.getString(R.string.dev_unlock_prompt_subtitle),
    )

    companion object {
        /** The navigation argument naming the sign-in request. */
        const val ARG_REQUEST_ID = "requestId"
    }
}
