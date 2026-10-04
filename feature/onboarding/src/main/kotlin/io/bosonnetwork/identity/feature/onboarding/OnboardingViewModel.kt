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
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.core.vault.KeyBackup
import io.bosonnetwork.identity.core.vault.wipe
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep {
    /** "Create a new identity" or "I already have one". */
    WELCOME,

    /** The new identity, before its backup. */
    CREATED,

    /** Saving the key: file, QR code or text. */
    BACKUP,

    /** Proving the key was saved, by two of its groups. */
    VERIFY,

    /** Bringing in an existing key. */
    RESTORE,

    /** Sealing the key in the vault, behind the screen lock. */
    PROTECT,
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    /** The identity being created or restored. */
    val userId: String? = null,
    /** The key's Base58, only while the backup is being made or checked. */
    val keyBase58: String? = null,
    val challenge: KeyBackup.Challenge? = null,
    val verifyFailed: Boolean = false,
    /** Whether the user has proved they hold a copy: a verified backup, or a restored key. */
    val backupVerified: Boolean = false,
    /** Whether the phone has a secure lock screen, without which nothing can be protected. */
    val canProtect: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    /** Set when the identity is safely in the vault: onboarding is over. */
    val finishedUserId: String? = null,
)

/**
 * Creating or restoring an identity on this phone. The key stays in memory only until it is sealed
 * in the vault; a new key is backed up (and, preferably, the backup checked) before that.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val vault: IdentityVault,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState(canProtect = vault.canProtectKeys()))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    // The key being onboarded. It never leaves this view model unsealed, and is destroyed once sealed.
    private var pending: Signature.KeyPair? = null

    fun create() {
        replacePending(Signature.KeyPair.random())
        _state.update { it.copy(step = OnboardingStep.CREATED, userId = userIdOf(pending!!), backupVerified = false, error = null) }
    }

    fun startRestore() = _state.update { it.copy(step = OnboardingStep.RESTORE, error = null) }

    /** Restores from a key file's text, a pasted key, or a scanned key QR. */
    fun restore(text: String) {
        val bytes = try {
            KeyBackup.parse(text)
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = context.getString(R.string.onb_restore_invalid)) }
            return
        }
        try {
            replacePending(Signature.KeyPair.fromPrivateKey(bytes))
        } finally {
            bytes.wipe()
        }
        // Whoever restores holds a copy already: that is the backup.
        _state.update {
            it.copy(step = OnboardingStep.PROTECT, userId = userIdOf(pending!!), backupVerified = true, error = null)
        }
    }

    fun startBackup() {
        val key = pending ?: return
        val bytes = key.privateKey().bytes()
        try {
            _state.update { it.copy(step = OnboardingStep.BACKUP, keyBase58 = KeyBackup.encode(bytes), error = null) }
        } finally {
            bytes.wipe()
        }
    }

    /** The key file's text, for the file the user chose. */
    fun keyFileText(): String? {
        val s = _state.value
        return KeyBackup.keyFileText(s.keyBase58 ?: return null, s.userId ?: return null)
    }

    fun startVerify() {
        val key = _state.value.keyBase58 ?: return
        _state.update {
            it.copy(step = OnboardingStep.VERIFY, challenge = KeyBackup.challenge(key), verifyFailed = false)
        }
    }

    fun verify(first: String, second: String) {
        val s = _state.value
        val key = s.keyBase58 ?: return
        val challenge = s.challenge ?: return
        if (KeyBackup.verify(key, challenge, first, second)) {
            _state.update { it.copy(step = OnboardingStep.PROTECT, keyBase58 = null, backupVerified = true, verifyFailed = false) }
        } else {
            _state.update { it.copy(verifyFailed = true) }
        }
    }

    /** "Remind me later": the identity is kept, but authorizes nothing until its backup is checked. */
    fun skipVerify() = _state.update { it.copy(step = OnboardingStep.PROTECT, keyBase58 = null, backupVerified = false) }

    /** Back to the key, from the check, to look at it again. */
    fun backToBackup() = _state.update { it.copy(step = OnboardingStep.BACKUP, verifyFailed = false) }

    fun refreshCanProtect() = _state.update { it.copy(canProtect = vault.canProtectKeys()) }

    /** Seals the key in the vault; the system asks the user to confirm with their screen lock. */
    fun protect() {
        val key = pending ?: return
        val s = _state.value
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val info = vault.add(
                    userKey = key,
                    name = null,
                    backupVerified = s.backupVerified,
                    keepUserKey = true,
                    prompt = AuthPrompt(
                        context.getString(R.string.onb_protect_prompt_title),
                        context.getString(R.string.onb_protect_prompt_subtitle),
                    ),
                )
                replacePending(null)
                _state.update { it.copy(busy = false, finishedUserId = info.userId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.userMessage(context)) }
            }
        }
    }

    /** Back to the start; a key not yet sealed is dropped. */
    fun restart() {
        replacePending(null)
        _state.value = OnboardingUiState(canProtect = vault.canProtectKeys())
    }

    override fun onCleared() {
        replacePending(null)
    }

    private fun replacePending(key: Signature.KeyPair?) {
        pending?.destroy()
        pending = key
    }

    private fun userIdOf(key: Signature.KeyPair) = Id.of(key.publicKey().bytes()).toString()
}
