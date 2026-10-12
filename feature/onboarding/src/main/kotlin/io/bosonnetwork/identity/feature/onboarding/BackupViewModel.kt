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
import io.bosonnetwork.crypto.KeyBackup
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.core.vault.wipe
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BackupStep { UNLOCK, BACKUP, VERIFY }

data class BackupUiState(
    val step: BackupStep = BackupStep.UNLOCK,
    val keyBase58: String? = null,
    val challenge: KeyBackup.Challenge? = null,
    val verifyFailed: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** Set when the backup is checked and recorded. */
    val done: Boolean = false,
)

/**
 * Backing up an identity already on the phone: the user key is unsealed (the user confirms), shown
 * for saving, then checked; the vault records that the backup exists.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val vault: IdentityVault,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val userId: String = checkNotNull(savedState[JoinNodeViewModel.ARG_USER_ID])

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun unlock() {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val prompt = AuthPrompt(
                    context.getString(R.string.onb_backup_prompt_title),
                    context.getString(R.string.onb_backup_prompt_subtitle),
                )
                val key = vault.withUserKey(userId, prompt) { keyPair ->
                    val bytes = keyPair.privateKey().bytes()
                    try {
                        KeyBackup.encode(bytes)
                    } finally {
                        bytes.wipe()
                    }
                }
                _state.update { it.copy(busy = false, step = BackupStep.BACKUP, keyBase58 = key) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.userMessage(context)) }
            }
        }
    }

    fun keyFileText(): String? = _state.value.keyBase58?.let { KeyBackup.keyFileText(it, userId) }

    fun startVerify() {
        val key = _state.value.keyBase58 ?: return
        _state.update { it.copy(step = BackupStep.VERIFY, challenge = KeyBackup.challenge(key), verifyFailed = false) }
    }

    fun backToBackup() = _state.update { it.copy(step = BackupStep.BACKUP, verifyFailed = false) }

    fun verify(first: String, second: String) {
        val s = _state.value
        val key = s.keyBase58 ?: return
        val challenge = s.challenge ?: return
        if (!KeyBackup.verify(key, challenge, first, second)) {
            _state.update { it.copy(verifyFailed = true) }
            return
        }
        viewModelScope.launch {
            vault.update(userId) { it.copy(backupVerified = true) }
            _state.update { it.copy(keyBase58 = null, done = true) }
        }
    }
}
