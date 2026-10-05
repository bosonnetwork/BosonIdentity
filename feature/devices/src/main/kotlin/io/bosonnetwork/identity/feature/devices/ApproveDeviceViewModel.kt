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
import io.bosonnetwork.director.client.Device
import io.bosonnetwork.director.client.PairingCode
import io.bosonnetwork.director.client.PendingDevice
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

enum class ApproveStep {
    /** Scanning the pairing code the new device shows, or pasting it. */
    SCAN,

    /** Reading the request the code is for. */
    LOADING,

    /** Who is asking, from where, and for what; approve or deny. */
    REVIEW,

    /** The account has a passphrase, and approving needs it. */
    PASSPHRASE,

    /** Approving or denying. */
    WORKING,

    /** The new device joined the identity. */
    DONE,

    /** The request was denied. */
    DENIED,

    /** There is no such request any more: it expired, or it was already answered. */
    EXPIRED,

    /** The request couldn't be read. */
    ERROR,
}

/** A device asking to join the identity, as the review shows it. */
data class PendingView(
    val deviceId: String,
    /** The name it gave itself; may be empty. */
    val name: String,
    /** The app asking; may be empty. */
    val app: String,
    /** [Device.KIND_APP] or [Device.KIND_PASSKEY]. */
    val kind: String,
    /** Whether it asks for the identity's key itself, to act fully as the user. */
    val wantsUserKey: Boolean,
    /** The address the request came from, as the node saw it. */
    val requestedFrom: String?,
)

data class ApproveUiState(
    val identity: IdentityInfo? = null,
    val step: ApproveStep = ApproveStep.SCAN,
    /** The pairing code as pasted. */
    val pasted: String = "",
    val pending: PendingView? = null,
    /** Seconds left before the request expires, counted on this phone. */
    val remainingSeconds: Long = 0,
    /** The countdown reached zero: the request can no longer be approved. */
    val expired: Boolean = false,
    /** The user ticked "I understand" for a request that wants the identity's key. */
    val understood: Boolean = false,
    /** The identity is an administrator at its node, so the new device may be made an administrator's. */
    val canAdmin: Boolean = false,
    /** The user chose to make the new device an administrator's. */
    val admin: Boolean = false,
    /** The account has a passphrase, so approving asks for it first. */
    val needsPassphrase: Boolean = false,
    val passphrase: String = "",
    val error: String? = null,
) {
    /** Approving is allowed: the backup is checked, the request is alive, and a key handover is understood. */
    val canApprove: Boolean
        get() = pending != null && !expired && identity?.backupVerified == true && (!pending.wantsUserKey || understood)
}

/**
 * Approving a new device that asks to join the identity, from the pairing code it shows. Reading
 * and denying the request go through this app's device key; approving is an act of authority,
 * confirmed with the user key every time, and refused until the key's backup is checked.
 */
@HiltViewModel
class ApproveDeviceViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val vault: IdentityVault,
    private val nodes: NodeConnections,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val userId: String = checkNotNull(savedState[ARG_USER_ID]) { "No identity to approve for" }

    private val _state = MutableStateFlow(ApproveUiState())
    val state: StateFlow<ApproveUiState> = _state.asStateFlow()

    /** This phone's clock for the countdown, in milliseconds; monotonic, so changing the time doesn't move it. */
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    private var code: PairingCode? = null
    private var job: Job? = null
    private var ticker: Job? = null

    // The scanner repeats the same payload every frame; act on the first one only.
    private var lastScanned: String? = null

    init {
        viewModelScope.launch {
            vault.load()
            // Kept current, so that a backup checked meanwhile allows approving.
            vault.identities.collect { list -> _state.update { it.copy(identity = list.firstOrNull { i -> i.userId == userId }) } }
        }
    }

    fun onScanned(text: String) {
        if (_state.value.step != ApproveStep.SCAN || text == lastScanned) return
        lastScanned = text
        open(text)
    }

    fun onPastedChange(value: String) = _state.update { it.copy(pasted = value, error = null) }

    fun submitPasted() {
        if (_state.value.step == ApproveStep.SCAN) open(_state.value.pasted)
    }

    fun onUnderstoodChange(value: Boolean) = _state.update { it.copy(understood = value) }

    fun onAdminChange(value: Boolean) = _state.update { it.copy(admin = value && it.canAdmin) }

    fun onPassphraseChange(value: String) = _state.update { it.copy(passphrase = value, error = null) }

    /** From the passphrase step, back to the request. */
    fun backToReview() {
        if (_state.value.step == ApproveStep.PASSPHRASE) _state.update { it.copy(step = ApproveStep.REVIEW, passphrase = "", error = null) }
    }

    /** Starts over with another code. */
    fun scanAgain() {
        job?.cancel()
        ticker?.cancel()
        code = null
        lastScanned = null
        _state.update { ApproveUiState(identity = it.identity) }
    }

    /** Reads the request again, after it couldn't be read. */
    fun retry() {
        code?.let { load(it) }
    }

    private fun open(text: String) {
        val parsed = try {
            PairingCode.parse(text.trim())
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = context.getString(R.string.dev_approve_invalid_code)) }
            return
        }
        load(parsed)
    }

    private fun load(code: PairingCode) {
        this.code = code
        job?.cancel()
        ticker?.cancel()
        _state.update {
            ApproveUiState(identity = it.identity, step = ApproveStep.LOADING, pasted = it.pasted)
        }
        job = viewModelScope.launch {
            try {
                val identity = _state.mapNotNull { it.identity }.first()
                val client = nodes.deviceClient(identity, unlockPrompt())
                val pending = call { client.getDeviceRegistration(code).await() }.orElse(null)
                // The node's clock and this phone's may differ: the countdown starts from the request's
                // lifetime and this phone's time of reading it, never from the two clocks compared.
                val fetchedAt = clock()
                if (pending == null) {
                    _state.update { it.copy(step = ApproveStep.EXPIRED) }
                    return@launch
                }
                // Whether the identity may make the device an administrator's, and whether the account
                // has a passphrase to ask for before the screen lock, not after.
                val profile = try {
                    call { client.profile.await() }
                } catch (e: AppError) {
                    null
                }
                _state.update {
                    it.copy(
                        step = ApproveStep.REVIEW,
                        pending = pending.toView(),
                        canAdmin = profile?.isAdmin == true,
                        needsPassphrase = profile?.isPassphraseProtected == true,
                    )
                }
                countDown(pending.expiresAt - pending.createdAt, fetchedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.AuthenticationCancelled) {
                lastScanned = null
                _state.update { it.copy(step = ApproveStep.SCAN, error = e.userMessage(context)) }
            } catch (e: Exception) {
                _state.update { it.copy(step = ApproveStep.ERROR, error = e.userMessage(context)) }
            }
        }
    }

    private fun countDown(lifetime: Long, fetchedAt: Long) {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                val left = lifetime - (clock() - fetchedAt)
                if (left <= 0) {
                    _state.update {
                        it.copy(
                            remainingSeconds = 0,
                            expired = true,
                            step = if (it.step == ApproveStep.PASSPHRASE) ApproveStep.REVIEW else it.step,
                        )
                    }
                    return@launch
                }
                _state.update { it.copy(remainingSeconds = (left + 999) / 1000) }
                delay(minOf(1000L, left))
            }
        }
    }

    /**
     * Approves the request, after the user confirms with their screen lock. An account with a
     * passphrase is asked for it first; one the node asks for anyway is asked for, and approving
     * again sends it. The user key goes to the new device only if it asked for it, sealed to its
     * pairing code; the administrator flag only if the user chose it and the identity is an admin.
     */
    fun approve() {
        val s = _state.value
        val identity = s.identity ?: return
        val node = identity.homeNode ?: return
        val code = code ?: return
        val pending = s.pending ?: return
        if (!s.canApprove) return
        val passphrase = when (s.step) {
            ApproveStep.REVIEW -> {
                if (s.needsPassphrase) {
                    _state.update { it.copy(step = ApproveStep.PASSPHRASE, passphrase = "", error = null) }
                    return
                }
                null
            }
            ApproveStep.PASSPHRASE -> s.passphrase.ifEmpty { return }
            else -> return
        }
        val admin = s.canAdmin && s.admin
        val back = s.step
        _state.update { it.copy(step = ApproveStep.WORKING, error = null) }
        job = viewModelScope.launch {
            try {
                val prompt = AuthPrompt(
                    context.getString(R.string.dev_approve_prompt_title),
                    context.getString(R.string.dev_approve_prompt_subtitle, displayName(pending), node.hostName()),
                )
                nodes.asUser(identity.userId, node, prompt) { client, _ ->
                    call { client.approveDeviceRegistration(code, passphrase, admin).await() }
                }
                ticker?.cancel()
                _state.update { it.copy(step = ApproveStep.DONE, passphrase = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.PassphraseRequired) {
                _state.update {
                    it.copy(
                        step = ApproveStep.PASSPHRASE,
                        needsPassphrase = true,
                        passphrase = "",
                        error = if (passphrase != null) e.userMessage(context) else null,
                    )
                }
            } catch (e: AppError.Forbidden) {
                // With a passphrase given, the node refuses a wrong one; without, only what isn't allowed.
                val message = when {
                    passphrase != null -> context.getString(R.string.dev_wrong_passphrase)
                    admin -> context.getString(R.string.dev_approve_admin_refused)
                    else -> e.userMessage(context)
                }
                fallBack(back, message)
            } catch (e: AppError.NotFound) {
                ticker?.cancel()
                _state.update { it.copy(step = ApproveStep.EXPIRED, passphrase = "") }
            } catch (e: Exception) {
                fallBack(back, e.userMessage(context))
            }
        }
    }

    /** Denies the request. Denying grants nothing, so this phone's key is enough. */
    fun deny() {
        val s = _state.value
        val identity = s.identity ?: return
        val code = code ?: return
        if (s.step != ApproveStep.REVIEW && s.step != ApproveStep.PASSPHRASE) return
        _state.update { it.copy(step = ApproveStep.WORKING, error = null) }
        job = viewModelScope.launch {
            try {
                val client = nodes.deviceClient(identity, unlockPrompt())
                call { client.denyDeviceRegistration(code).await() }
                ticker?.cancel()
                _state.update { it.copy(step = ApproveStep.DENIED, passphrase = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.NotFound) {
                ticker?.cancel()
                _state.update { it.copy(step = ApproveStep.EXPIRED, passphrase = "") }
            } catch (e: Exception) {
                fallBack(ApproveStep.REVIEW, e.userMessage(context))
            }
        }
    }

    // Back to where the user was, unless the request expired meanwhile: then to the review, which says so.
    private fun fallBack(step: ApproveStep, message: String) = _state.update {
        val to = if (it.expired && step == ApproveStep.PASSPHRASE) ApproveStep.REVIEW else step
        it.copy(step = to, passphrase = "", error = message)
    }

    private fun unlockPrompt() = AuthPrompt(
        context.getString(R.string.dev_unlock_prompt_title),
        context.getString(R.string.dev_unlock_prompt_subtitle),
    )

    private fun displayName(pending: PendingView): String =
        pending.name.ifBlank { pending.app }.ifBlank { context.getString(R.string.dev_approve_unnamed) }

    companion object {
        /** The navigation argument naming the identity the device joins. */
        const val ARG_USER_ID = "userId"
    }
}

private fun PendingDevice.toView() = PendingView(
    deviceId = deviceId.toString(),
    name = deviceName,
    app = appName,
    kind = kind,
    wantsUserKey = wantsUserKey(),
    requestedFrom = requestedFrom.orElse(null)?.takeIf { it.isNotBlank() },
)
