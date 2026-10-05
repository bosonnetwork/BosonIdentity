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

package io.bosonnetwork.identity.feature.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.exceptions.RegistrationDeniedException
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.ThisDevice
import io.bosonnetwork.identity.core.boson.call
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.NodeAddress
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

enum class MoveStep {
    /** Typing the home node's address, or choosing to scan its QR code. */
    ADDRESS,

    /** Scanning the home node's QR code. */
    SCAN,

    /** Asking the node, and filing the request to join. */
    CONNECTING,

    /** The pairing code is shown; waiting for the old phone to approve it. */
    PAIRING,

    /** Approved: storing the identity on this phone. */
    STORING,

    /** The old phone approved, but this phone couldn't store the identity; storing is tried again. */
    STORE_FAILED,

    /** The old phone denied the request. */
    DENIED,

    /** The request expired before the old phone answered, or the node lost it. */
    EXPIRED,
}

data class MoveUiState(
    val step: MoveStep = MoveStep.ADDRESS,
    val address: String = "",
    val nodeIdInput: String = "",
    /** The home node, with the id it reported. */
    val node: NodeAddress? = null,
    /** The pairing code to show the old phone, as a QR code. */
    val pairingCode: String? = null,
    val error: String? = null,
    /** Set when the identity is on this phone, joined to its node: moving is over. */
    val finishedUserId: String? = null,
)

/**
 * Moving the identity from the old phone to this one. This phone asks the home node to join the
 * identity as a new device that wants the identity's key, and shows the pairing code; the old phone
 * scans it and approves in Devices, sealing the key to the code, so the node relays it without being
 * able to read it. This phone then stores the key it received, with the device key the node now
 * knows, so it needs no joining step: it already is one of the identity's devices.
 */
@HiltViewModel
class MoveFromOldPhoneViewModel @Inject constructor(
    private val vault: IdentityVault,
    private val nodes: NodeConnections,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(MoveUiState())
    val state: StateFlow<MoveUiState> = _state.asStateFlow()

    private var job: Job? = null

    // This phone's new device key, in memory only until it is stored; a new one for each request.
    private var deviceKey: Signature.KeyPair? = null

    // The identity's key once the old phone handed it over, kept until it is stored.
    private var userKey: Signature.KeyPair? = null

    // The scanner repeats the same payload every frame; act on the first one only.
    private var lastScanned: String? = null

    fun onAddressChange(value: String) = _state.update { it.copy(address = value, error = null) }
    fun onNodeIdChange(value: String) = _state.update { it.copy(nodeIdInput = value, error = null) }

    fun scan() = _state.update { it.copy(step = MoveStep.SCAN, error = null) }

    /** Back to the node's address, dropping a request under way. */
    fun backToAddress() {
        job?.cancel()
        forgetKeys()
        lastScanned = null
        _state.update { it.copy(step = MoveStep.ADDRESS, node = null, pairingCode = null) }
    }

    fun onScanned(text: String) {
        if (_state.value.step != MoveStep.SCAN || text == lastScanned) return
        lastScanned = text
        val scanned = scannedNode(text)
        if (scanned == null) {
            _state.update { it.copy(step = MoveStep.ADDRESS, error = context.getString(R.string.onb_join_invalid_qr)) }
            return
        }
        _state.update { it.copy(address = scanned.url, nodeIdInput = scanned.nodeId, step = MoveStep.ADDRESS) }
        check()
    }

    /** Asks the node typed who it is, then files the request to join the identity there. */
    fun check() {
        val s = _state.value
        if (s.step != MoveStep.ADDRESS) return
        val typed = when (val checked = typedNode(s.address, s.nodeIdInput)) {
            is TypedNode.Invalid -> {
                _state.update { it.copy(error = context.getString(checked.message)) }
                return
            }
            is TypedNode.Valid -> checked.node
        }
        // The key will be sealed behind the screen lock: without one, there's no point asking the old phone.
        if (!vault.canProtectKeys()) {
            _state.update { it.copy(error = context.getString(R.string.onb_protect_no_lock)) }
            return
        }
        _state.update { it.copy(step = MoveStep.CONNECTING, error = null) }
        job = viewModelScope.launch {
            try {
                val node = nodes.identify(typed)
                _state.update { it.copy(node = node) }
                pair(node)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(step = MoveStep.ADDRESS, error = e.userMessage(context)) }
            }
        }
    }

    /** Files a new request after a denied or expired one, or stores the identity again after a failure. */
    fun tryAgain() {
        val s = _state.value
        val node = s.node ?: return
        when (s.step) {
            MoveStep.STORE_FAILED -> {
                _state.update { it.copy(step = MoveStep.STORING, error = null) }
                job = viewModelScope.launch { store(node) }
            }
            MoveStep.DENIED, MoveStep.EXPIRED -> {
                _state.update { it.copy(step = MoveStep.CONNECTING, pairingCode = null, error = null) }
                job = viewModelScope.launch { pair(node) }
            }
            else -> Unit
        }
    }

    // Asks to join the identity at [node] with a fresh device key, shows the pairing code, and waits
    // for the old phone's answer.
    private suspend fun pair(node: NodeAddress) {
        forgetKeys()
        val key = Signature.KeyPair.random()
        deviceKey = key
        try {
            val guest = nodes.guest(node)
            val registration = call {
                guest.requestDeviceRegistration(key, ThisDevice.name, ThisDevice.APP_NAME, true).await()
            }
            _state.update { it.copy(step = MoveStep.PAIRING, pairingCode = registration.pairingCode.toString()) }
            // The node holds this call until the old phone answers, or the request expires.
            val approval = call { guest.finishDeviceRegistration(registration).await() }
            val handedOver = approval.userKey.orElse(null)
            if (handedOver == null || Id.of(handedOver.publicKey().bytes()) != approval.userId) {
                handedOver?.destroy()
                _state.update { it.copy(step = MoveStep.EXPIRED, pairingCode = null, error = context.getString(R.string.onb_move_no_key)) }
                return
            }
            userKey = handedOver
            _state.update { it.copy(step = MoveStep.STORING, pairingCode = null) }
            store(node)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.Conflict) {
            // A denied request is answered 412, which reads as a conflict; any other conflict is shown.
            if (e.cause is RegistrationDeniedException) {
                _state.update { it.copy(step = MoveStep.DENIED, pairingCode = null) }
            } else {
                _state.update { it.copy(step = MoveStep.EXPIRED, pairingCode = null, error = e.userMessage(context)) }
            }
        } catch (e: AppError.Timeout) {
            _state.update { it.copy(step = MoveStep.EXPIRED, pairingCode = null) }
        } catch (e: AppError.NotFound) {
            _state.update { it.copy(step = MoveStep.EXPIRED, pairingCode = null) }
        } catch (e: Exception) {
            _state.update { it.copy(step = MoveStep.EXPIRED, pairingCode = null, error = e.userMessage(context)) }
        }
    }

    // Seals the identity's key and this phone's device key in the vault, and records the home node and
    // this phone's device there: the node already registered it when the old phone approved.
    private suspend fun store(node: NodeAddress) {
        val user = userKey ?: return
        val device = deviceKey ?: return
        try {
            val info = vault.add(
                userKey = user,
                name = null,
                // This phone hasn't seen the backup checked; the identity asks for it again here.
                backupVerified = false,
                keepUserKey = true,
                prompt = AuthPrompt(
                    context.getString(R.string.onb_protect_prompt_title),
                    context.getString(R.string.onb_protect_prompt_subtitle),
                ),
                deviceKey = device,
            )
            val deviceId = Id.of(device.publicKey().bytes()).toString()
            vault.update(info.userId) { it.copy(homeNode = node, deviceId = deviceId) }
            forgetKeys()
            _state.update { it.copy(finishedUserId = info.userId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.Conflict) {
            // Already on this phone: nothing to store, and nothing to keep in memory.
            forgetKeys()
            _state.update { it.copy(step = MoveStep.ADDRESS, node = null, error = context.getString(R.string.onb_move_already_here)) }
        } catch (e: Exception) {
            _state.update { it.copy(step = MoveStep.STORE_FAILED, error = e.userMessage(context)) }
        }
    }

    private fun forgetKeys() {
        userKey?.destroy()
        userKey = null
        deviceKey?.destroy()
        deviceKey = null
    }

    override fun onCleared() {
        forgetKeys()
    }
}
