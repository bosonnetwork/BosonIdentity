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
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.ProfileSync
import io.bosonnetwork.identity.core.boson.ThisDevice
import io.bosonnetwork.identity.core.boson.call
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.core.vault.MoveCode
import io.bosonnetwork.identity.core.vault.MovePayload
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MoveStep {
    /** Scanning the QR code the old phone shows. */
    SCAN,

    /** Typing the code the old phone showed. */
    CODE,

    /** Opening the QR code with the code, then keeping the identity here. */
    OPENING,

    /** Adding this phone to the identity's home node. */
    JOINING,

    /** The account has a passphrase, which adding this phone needs. */
    PASSPHRASE,

    /** The home node couldn't add this phone; it can be tried again, or later. */
    JOIN_FAILED,
}

data class MoveUiState(
    val step: MoveStep = MoveStep.SCAN,
    /** The code as typed. */
    val code: String = "",
    val passphrase: String = "",
    /** The home node the identity moved with, while this phone joins it. */
    val node: NodeAddress? = null,
    val error: String? = null,
    /** Set when the identity is on this phone: [userId] is its id. */
    val finishedUserId: String? = null,
)

/**
 * Moving the identity here from the old phone, which shows a QR code and, before it, a code
 * ([MoveCode]): no node is needed for the move itself. The QR code is scanned, the code typed, and the
 * identity (its key, its home node, its profile) is kept here under this phone's screen lock. With a
 * home node, this phone is then added to it as one of the identity's devices, by the user key it now
 * holds, and the profile synced; without one, the identity is here, its profile as the old phone had it.
 */
@HiltViewModel
class MoveFromOldPhoneViewModel @Inject constructor(
    private val vault: IdentityVault,
    private val nodes: NodeConnections,
    private val profileSync: ProfileSync,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(MoveUiState())
    val state: StateFlow<MoveUiState> = _state.asStateFlow()

    // The scanned QR code, until it is opened; the scanner repeats it every frame.
    private var scanned: String? = null

    // The identity being joined to its node, after it was kept here.
    private var userId: String? = null

    fun onScanned(text: String) {
        if (_state.value.step != MoveStep.SCAN || text == scanned) return
        if (!MoveCode.isMoveCode(text)) {
            _state.update { it.copy(error = context.getString(R.string.onb_move_not_a_code)) }
            return
        }
        scanned = text
        _state.update { it.copy(step = MoveStep.CODE, error = null) }
    }

    fun onCodeChange(value: String) = _state.update { it.copy(code = value, error = null) }
    fun onPassphraseChange(value: String) = _state.update { it.copy(passphrase = value, error = null) }

    /** Back to the scanner, for another QR code. */
    fun scanAgain() {
        scanned = null
        _state.update { MoveUiState() }
    }

    /** Opens the QR code with the typed code, then keeps the identity here. */
    fun open() {
        val text = scanned ?: return
        val s = _state.value
        if (s.step != MoveStep.CODE) return
        if (MoveCode.normalize(s.code) == null) {
            _state.update { it.copy(error = context.getString(R.string.onb_move_code_invalid)) }
            return
        }
        _state.update { it.copy(step = MoveStep.OPENING, error = null) }
        viewModelScope.launch {
            val payload = try {
                // Deriving the key from the code takes a moment, and memory: off the main thread.
                withContext(Dispatchers.Default) { MoveCode.open(text, s.code) }
            } catch (e: MoveCode.WrongCode) {
                _state.update { it.copy(step = MoveStep.CODE, error = context.getString(R.string.onb_move_code_wrong)) }
                return@launch
            } catch (e: MoveCode.NotAMoveCode) {
                scanned = null
                _state.update { it.copy(step = MoveStep.SCAN, code = "", error = context.getString(R.string.onb_move_not_a_code)) }
                return@launch
            }
            keep(payload)
        }
    }

    // Seals the key here, with the profile; then joins the home node, if there is one.
    private suspend fun keep(payload: MovePayload) {
        val userKey = Signature.KeyPair.fromPrivateKey(payload.privateKey)
        payload.privateKey.fill(0)
        try {
            val info = vault.add(
                userKey = userKey,
                name = payload.profile.fields.name,
                backupVerified = payload.backupVerified,
                keepUserKey = true,
                prompt = AuthPrompt(
                    context.getString(R.string.onb_protect_prompt_title),
                    context.getString(R.string.onb_protect_prompt_subtitle),
                ),
            )
            val fields = payload.profile.fields
            profileSync.edit(info.userId, fields.name, fields.bio, fields.email)
            userId = info.userId
            val node = payload.homeNode
            if (node == null) {
                _state.update { it.copy(finishedUserId = info.userId) }
            } else {
                _state.update { it.copy(node = node) }
                join(info.userId, node, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.Conflict) {
            scanned = null
            _state.update { MoveUiState(error = context.getString(R.string.onb_move_already_here)) }
        } catch (e: AppError.AuthenticationCancelled) {
            _state.update { it.copy(step = MoveStep.CODE) }
        } catch (e: Exception) {
            _state.update { it.copy(step = MoveStep.CODE, error = e.userMessage(context)) }
        } finally {
            userKey.destroy()
        }
    }

    /** Adds this phone to the home node again, after it failed or asked for the passphrase. */
    fun retryJoin() {
        val id = userId ?: return
        val node = _state.value.node ?: return
        val passphrase = _state.value.passphrase.ifEmpty { null }
        viewModelScope.launch { join(id, node, passphrase) }
    }

    /** Leaves the joining for later: the identity is here; the Identity tab offers to join its node. */
    fun joinLater() {
        _state.value.let { s -> userId?.let { id -> _state.update { s.copy(finishedUserId = id) } } }
    }

    // Registers this phone with the identity's home node, by the user key, then syncs the profile.
    private suspend fun join(userId: String, node: NodeAddress, passphrase: String?) {
        _state.update { it.copy(step = MoveStep.JOINING, error = null) }
        try {
            val prompt = AuthPrompt(
                context.getString(R.string.onb_join_prompt_title),
                context.getString(R.string.onb_join_prompt_subtitle, node.url),
            )
            val known = nodes.identify(node)
            val deviceId = nodes.asUser(userId, known, prompt, withDeviceKey = true) { client, deviceKey ->
                val key = checkNotNull(deviceKey)
                try {
                    call { client.registerDevice(key, ThisDevice.name, ThisDevice.APP_NAME, passphrase).await() }
                } catch (e: AppError.Conflict) {
                    // Already one of the identity's devices.
                }
                Id.of(key.publicKey().bytes()).toString()
            }
            vault.update(userId) { it.copy(homeNode = known, deviceId = deviceId) }
            try {
                profileSync.sync(userId, prompt, passphrase)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Here and joined: the Identity tab syncs again.
            }
            _state.update { it.copy(passphrase = "", finishedUserId = userId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.PassphraseRequired) {
            _state.update { it.copy(step = MoveStep.PASSPHRASE, passphrase = "") }
        } catch (e: AppError.Forbidden) {
            _state.update { it.copy(step = MoveStep.PASSPHRASE, passphrase = "", error = context.getString(R.string.onb_move_wrong_passphrase)) }
        } catch (e: Exception) {
            _state.update { it.copy(step = MoveStep.JOIN_FAILED, error = e.userMessage(context)) }
        }
    }
}
