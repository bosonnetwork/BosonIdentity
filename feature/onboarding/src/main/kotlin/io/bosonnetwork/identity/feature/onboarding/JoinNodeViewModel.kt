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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.Id
import io.bosonnetwork.director.client.UserRegistration
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class JoinStep {
    /** Typing the node's address, or choosing to scan its QR code. */
    ADDRESS,

    /** Scanning the node's QR code. */
    SCAN,

    /** The node answered: who it is, and what joining means. */
    CONFIRM,

    /** Joining: registering the identity, or just this phone, with the node. */
    WORKING,

    /** The account has a passphrase, and adding this phone needs it. */
    PASSPHRASE,
}

data class JoinNodeUiState(
    val step: JoinStep = JoinStep.ADDRESS,
    val address: String = "",
    val nodeIdInput: String = "",
    /** The node, with the id it reported (or the one its QR code carried and it confirmed). */
    val node: NodeAddress? = null,
    val acceptsNewAccounts: Boolean = false,
    val name: String = "",
    val passphrase: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** Set when the identity's home node is recorded. */
    val joined: Boolean = false,
)

/**
 * Joins the identity to its home node. One confirmation with the user key does it all: if the node
 * already knows the identity (a restored one), this phone is added as a device; if not, the identity
 * registers as a new user, with this phone as its first device, solving the node's proof of work.
 */
@HiltViewModel
class JoinNodeViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val vault: IdentityVault,
    private val nodes: NodeConnections,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val userId: String = checkNotNull(savedState[ARG_USER_ID]) { "No identity to join" }

    private val _state = MutableStateFlow(JoinNodeUiState())
    val state: StateFlow<JoinNodeUiState> = _state.asStateFlow()

    fun onAddressChange(value: String) = _state.update { it.copy(address = value, error = null) }
    fun onNodeIdChange(value: String) = _state.update { it.copy(nodeIdInput = value, error = null) }
    fun onNameChange(value: String) = _state.update { it.copy(name = value) }
    fun onPassphraseChange(value: String) = _state.update { it.copy(passphrase = value, error = null) }

    fun scan() = _state.update { it.copy(step = JoinStep.SCAN, error = null) }
    fun backToAddress() = _state.update { it.copy(step = JoinStep.ADDRESS, node = null, error = null) }

    // The scanner repeats the same payload every frame; act on the first one only.
    private var lastScanned: String? = null

    /** A node's QR code: `{"url": ..., "id": ...}`, as the node's home page and the portal show it. */
    fun onScanned(text: String) {
        if (text == lastScanned) return
        lastScanned = text
        val json = runCatching { JSONObject(text) }.getOrNull()
        val url = json?.optString("url").orEmpty().trim()
        if (url.isEmpty()) {
            _state.update { it.copy(step = JoinStep.ADDRESS, error = context.getString(R.string.onb_join_invalid_qr)) }
            return
        }
        _state.update { it.copy(address = url, nodeIdInput = json?.optString("id").orEmpty().trim(), step = JoinStep.ADDRESS) }
        check()
    }

    /** Asks the node who it is and how it takes new accounts. */
    fun check() {
        val s = _state.value
        val typed = try {
            NodeAddress.parse(s.address, s.nodeIdInput.ifBlank { null })
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = context.getString(R.string.onb_join_invalid_address)) }
            return
        }
        val pinned = typed.nodeId
        if (pinned != null && runCatching { Id.of(pinned) }.isFailure) {
            _state.update { it.copy(error = context.getString(R.string.onb_join_invalid_node_id)) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val node = nodes.identify(typed)
                val options = call { nodes.guest(node).registrationOptions.await() }
                _state.update {
                    it.copy(busy = false, step = JoinStep.CONFIRM, node = node, acceptsNewAccounts = options.isProofOfWorkEnabled)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.userMessage(context)) }
            }
        }
    }

    /** Joins, after the user confirms with their screen lock. */
    fun join() {
        val s = _state.value
        val node = s.node ?: return
        _state.update { it.copy(step = JoinStep.WORKING, busy = true, error = null) }
        viewModelScope.launch {
            try {
                val prompt = AuthPrompt(
                    context.getString(R.string.onb_join_prompt_title),
                    context.getString(R.string.onb_join_prompt_subtitle, node.url),
                )
                val deviceId = nodes.asUser(userId, node, prompt, withDeviceKey = true) { client, deviceKey ->
                    val key = checkNotNull(deviceKey)
                    val member = try {
                        call { client.profile.await() }
                        true
                    } catch (e: AppError.Unauthorized) {
                        false
                    } catch (e: AppError.NotFound) {
                        false
                    }
                    if (member) {
                        try {
                            call {
                                client.registerDevice(key, ThisDevice.name, ThisDevice.APP_NAME, s.passphrase.ifEmpty { null }).await()
                            }
                        } catch (e: AppError.Conflict) {
                            // This phone is already one of the identity's devices.
                        }
                    } else {
                        if (!s.acceptsNewAccounts) throw NoNewAccounts()
                        val registration = UserRegistration()
                            .name(s.name.trim().ifEmpty { null })
                            .initialDevice(ThisDevice.name, ThisDevice.APP_NAME)
                        call { client.registerUser(registration).await() }
                    }
                    Id.of(key.publicKey().bytes()).toString()
                }
                vault.update(userId) { it.copy(homeNode = node, deviceId = deviceId) }
                _state.update { it.copy(busy = false, joined = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.PassphraseRequired) {
                _state.update { it.copy(busy = false, step = JoinStep.PASSPHRASE, passphrase = "") }
            } catch (e: NoNewAccounts) {
                _state.update { it.copy(busy = false, step = JoinStep.CONFIRM, error = context.getString(R.string.onb_join_no_new_accounts)) }
            } catch (e: Exception) {
                val back = if (s.passphrase.isNotEmpty()) JoinStep.PASSPHRASE else JoinStep.CONFIRM
                _state.update { it.copy(busy = false, step = back, error = e.userMessage(context)) }
            }
        }
    }

    private class NoNewAccounts : Exception()

    companion object {
        /** The navigation argument naming the identity to join. */
        const val ARG_USER_ID = "userId"
    }
}
