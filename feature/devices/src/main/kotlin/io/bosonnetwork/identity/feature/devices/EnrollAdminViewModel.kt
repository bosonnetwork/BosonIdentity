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
import io.bosonnetwork.director.client.EnrollmentCode
import io.bosonnetwork.director.client.PendingEnrollment
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EnrollStep {
    /** What enrolling means, for this identity at the code's node; continue or leave. */
    REVIEW,

    /** The identity's home node is another node: it can't administer this one. */
    OTHER_NODE,

    /** Claiming the request, with the user key. */
    CLAIMING,

    /** Claimed: the number to type on the node's console, while the console answers. */
    NUMBER,

    /** Approved: the identity administers the node. */
    DONE,

    /** Denied on the console, or after wrong numbers. */
    DENIED,

    /** Another phone claimed the code first. */
    TAKEN,

    /** The request is no more: it expired, or it ended. */
    EXPIRED,

    /** Something failed; [EnrollUiState.error] says what. */
    ERROR,
}

data class EnrollUiState(
    val identity: IdentityInfo? = null,
    val step: EnrollStep = EnrollStep.REVIEW,
    /** The node the code is for: its Director's address and id. */
    val node: NodeAddress? = null,
    /** The number to type on the console, once claimed. */
    val number: Int? = null,
    /** Seconds left before the request expires, counted on this phone. */
    val remainingSeconds: Long = 0,
    val error: String? = null,
) {
    /** Claiming is allowed: the backup is checked, and the identity may live at the code's node. */
    val canClaim: Boolean
        get() = step == EnrollStep.REVIEW && identity?.backupVerified == true
}

/**
 * Enrolling the identity as an administrator of a node, from the QR code the node's console shows
 * (`boson-director-cli user enroll-admin`). The code carries no credential. The identity claims the
 * request with its user key and this app's device key (an act of authority, confirmed with the user key
 * and refused until the key's backup is checked), gets a number, and shows it; the owner types it on the
 * console. On approval the node is the identity's home node, this phone one of its devices, and the
 * identity its administrator.
 *
 * An identity has one home node: an identity that lives on another node is refused.
 */
@HiltViewModel
class EnrollAdminViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val vault: IdentityVault,
    private val nodes: NodeConnections,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val userId: String = checkNotNull(savedState[ApproveDeviceViewModel.ARG_USER_ID]) { "No identity to enroll" }
    private val code: EnrollmentCode = EnrollmentCode.parse(checkNotNull(savedState[ARG_CODE]) { "No enrollment code" })
        .orElseThrow { IllegalArgumentException("Not an enrollment code") }

    private val _state = MutableStateFlow(EnrollUiState(node = NodeAddress(code.url, code.nodeId.toString())))
    val state: StateFlow<EnrollUiState> = _state.asStateFlow()

    /** This phone's clock for the countdown, in milliseconds; monotonic. */
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    private var pending: PendingEnrollment? = null
    private var job: Job? = null
    private var ticker: Job? = null

    init {
        viewModelScope.launch {
            vault.load()
            // Kept current, so that a backup checked meanwhile allows claiming.
            vault.identities.collect { list ->
                val identity = list.firstOrNull { it.userId == userId }
                _state.update {
                    val step = if (it.step == EnrollStep.REVIEW && identity?.livesElsewhere() == true) EnrollStep.OTHER_NODE else it.step
                    it.copy(identity = identity, step = step)
                }
            }
        }
    }

    // A home node of its own that is not the code's node. One known only by its address is compared by it.
    private fun IdentityInfo.livesElsewhere(): Boolean {
        val home = homeNode ?: return false
        return if (home.nodeId != null) home.nodeId != code.nodeId.toString() else home.url.trimEnd('/') != code.url
    }

    /** Claims the request, after the user confirms with their screen lock, and waits for the console. */
    fun claim() {
        val s = _state.value
        if (!s.canClaim) return
        val node = s.node ?: return
        _state.update { it.copy(step = EnrollStep.CLAIMING, error = null) }
        job = viewModelScope.launch {
            try {
                val prompt = AuthPrompt(
                    context.getString(R.string.dev_enroll_prompt_title),
                    context.getString(R.string.dev_enroll_prompt_subtitle, node.hostName()),
                )
                val claimed = nodes.claimEnrollment(userId, code, prompt)
                pending = claimed
                val lifetime = claimed.expiresAt - System.currentTimeMillis()
                _state.update { it.copy(step = EnrollStep.NUMBER, number = claimed.number) }
                countDown(lifetime, clock())
                await(claimed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.AuthenticationCancelled) {
                _state.update { it.copy(step = EnrollStep.REVIEW) }
            } catch (e: AppError.Conflict) {
                _state.update { it.copy(step = EnrollStep.TAKEN) }
            } catch (e: AppError.NotFound) {
                _state.update { it.copy(step = EnrollStep.EXPIRED) }
            } catch (e: AppError.Forbidden) {
                _state.update { it.copy(step = EnrollStep.ERROR, error = context.getString(R.string.dev_enroll_deactivated)) }
            } catch (e: Exception) {
                _state.update { it.copy(step = EnrollStep.ERROR, error = e.userMessage(context)) }
            }
        }
    }

    /** Waits for the console again, after the wait failed (the network dropped, for one). */
    fun retry() {
        val claimed = pending
        if (claimed == null) {
            _state.update { it.copy(step = EnrollStep.REVIEW, error = null) }
            return
        }
        _state.update { it.copy(step = EnrollStep.NUMBER, error = null) }
        job?.cancel()
        job = viewModelScope.launch {
            try {
                await(claimed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(step = EnrollStep.ERROR, error = e.userMessage(context)) }
            }
        }
    }

    // Waits for the console's answer and, on approval, records the node as the identity's administered home.
    private suspend fun await(claimed: PendingEnrollment) {
        try {
            nodes.finishEnrollment(claimed)
        } catch (e: AppError.Conflict) {
            ticker?.cancel()
            _state.update { it.copy(step = EnrollStep.DENIED) }
            return
        } catch (e: AppError.Timeout) {
            ticker?.cancel()
            _state.update { it.copy(step = EnrollStep.EXPIRED) }
            return
        } catch (e: AppError.NotFound) {
            ticker?.cancel()
            _state.update { it.copy(step = EnrollStep.EXPIRED) }
            return
        }
        ticker?.cancel()
        val node = NodeAddress(code.url, code.nodeId.toString())
        vault.update(userId) {
            it.copy(homeNode = it.homeNode ?: node, deviceId = claimed.deviceId.toString(), administers = true)
        }
        _state.update { it.copy(step = EnrollStep.DONE) }
    }

    private fun countDown(lifetime: Long, startedAt: Long) {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                val left = lifetime - (clock() - startedAt)
                if (left <= 0) {
                    _state.update { it.copy(remainingSeconds = 0) }
                    return@launch
                }
                _state.update { it.copy(remainingSeconds = (left + 999) / 1000) }
                delay(minOf(1000L, left))
            }
        }
    }

    companion object {
        /** The navigation argument carrying the scanned enrollment code. */
        const val ARG_CODE = "code"
    }
}
