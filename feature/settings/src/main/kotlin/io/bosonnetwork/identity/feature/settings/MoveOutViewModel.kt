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

package io.bosonnetwork.identity.feature.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.core.vault.MoveCode
import io.bosonnetwork.identity.core.vault.MovePayload
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MoveOutStep {
    /** What moving does, and that the next screens are private. */
    INTRO,

    /** Sealing the identity under a new code. */
    SEALING,

    /** The code to type on the new phone, on its own. */
    CODE,

    /** The QR code for the new phone to scan. */
    QR,

    /** The QR code timed out or was put away: moved, or to be shown again. */
    DONE,
}

data class MoveOutUiState(
    val step: MoveOutStep = MoveOutStep.INTRO,
    /** The code as shown: `XXXX-XXXX-XX`. */
    val code: String? = null,
    /** The QR code's text: the identity sealed under the code. */
    val qr: String? = null,
    /** Seconds left before the QR code hides itself. */
    val remainingSeconds: Long = 0,
    /** The identity has no home node: the avatar stays on this phone. */
    val noNode: Boolean = false,
    val error: String? = null,
)

/**
 * Moving the identity to a new phone, on the old phone: after a warning, the user key confirms; the app
 * makes a code and seals the identity (its key, home node and profile) under it ([MoveCode]); it shows the
 * code on its own, then the QR code, which hides itself after [QR_SECONDS]. Works with or without a home
 * node.
 */
@HiltViewModel
class MoveOutViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val vault: IdentityVault,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val userId: String = checkNotNull(savedState[ARG_USER_ID]) { "No identity to move" }

    private val _state = MutableStateFlow(MoveOutUiState())
    val state: StateFlow<MoveOutUiState> = _state.asStateFlow()

    private var ticker: Job? = null

    init {
        viewModelScope.launch {
            vault.load()
            _state.update { it.copy(noNode = vault.identities.value.firstOrNull { i -> i.userId == userId }?.homeNode == null) }
        }
    }

    /** Confirms with the user key, then makes the code and seals the identity under it. */
    fun start() {
        if (_state.value.step != MoveOutStep.INTRO && _state.value.step != MoveOutStep.DONE) return
        _state.update { it.copy(step = MoveOutStep.SEALING, error = null) }
        viewModelScope.launch {
            try {
                val identity = vault.identities.value.first { it.userId == userId }
                val prompt = AuthPrompt(
                    context.getString(R.string.set_move_prompt_title),
                    context.getString(R.string.set_move_prompt_subtitle),
                )
                val code = MoveCode.newCode()
                val qr = vault.withUserKey(userId, prompt) { key ->
                    val payload = MovePayload(key.privateKey().bytes(), identity.homeNode, identity.backupVerified, identity.profile)
                    // Deriving the key from the code takes a moment, and memory: off the main thread.
                    withContext(Dispatchers.Default) { MoveCode.seal(payload, code) }
                }
                _state.update { it.copy(step = MoveOutStep.CODE, code = code, qr = qr) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.AuthenticationCancelled) {
                _state.update { it.copy(step = MoveOutStep.INTRO) }
            } catch (e: Exception) {
                _state.update { it.copy(step = MoveOutStep.INTRO, error = e.userMessage(context)) }
            }
        }
    }

    /** The code is noted: the QR code shows, for [QR_SECONDS]. */
    fun showQr() {
        if (_state.value.step != MoveOutStep.CODE) return
        _state.update { it.copy(step = MoveOutStep.QR, remainingSeconds = QR_SECONDS) }
        ticker?.cancel()
        ticker = viewModelScope.launch {
            var left = QR_SECONDS
            while (left > 0) {
                delay(1000)
                left--
                _state.update { it.copy(remainingSeconds = left) }
            }
            hide()
        }
    }

    /** Back from the QR code to the code, to read it again. */
    fun backToCode() {
        if (_state.value.step == MoveOutStep.QR) {
            ticker?.cancel()
            _state.update { it.copy(step = MoveOutStep.CODE) }
        }
    }

    /** Puts the QR code and the code away: neither stays on screen or in memory. */
    fun hide() {
        ticker?.cancel()
        _state.update { it.copy(step = MoveOutStep.DONE, code = null, qr = null, remainingSeconds = 0) }
    }

    override fun onCleared() {
        ticker?.cancel()
    }

    companion object {
        /** The navigation argument naming the identity to move. */
        const val ARG_USER_ID = "userId"

        /** How long the QR code stays on screen. */
        const val QR_SECONDS = 120L
    }
}
