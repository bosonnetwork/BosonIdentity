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
import io.bosonnetwork.identity.core.backup.PasskeyBackup
import io.bosonnetwork.identity.core.backup.PrfUnsupportedException
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.vault.AuthPrompt
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BackupStatus {
    /** Not known yet. */
    CHECKING,

    /** A passkey backup of the identity is in Block Store. */
    ON,
    OFF,

    /** This phone can't keep one: no Google Play services, or no Google account to back up to. */
    UNAVAILABLE,
}

data class PasskeyBackupUiState(
    val identity: IdentityInfo? = null,
    val status: BackupStatus = BackupStatus.CHECKING,
    /** Set while the warning before turning it on is shown. */
    val confirming: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * The opt-in passkey backup of the identity in use (plan section 3): a copy of the user key that a
 * passkey of the user's password manager can open on a new phone. Turning it on takes the identity's own
 * checked backup first, a warning about who could then restore the identity, a new passkey, and the
 * screen lock to open the user key.
 */
@HiltViewModel
class PasskeyBackupViewModel @Inject constructor(
    private val activeIdentity: ActiveIdentity,
    private val backup: PasskeyBackup,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(PasskeyBackupUiState())
    val state: StateFlow<PasskeyBackupUiState> = _state.asStateFlow()

    private var checking: Job? = null

    init {
        viewModelScope.launch {
            activeIdentity.active.collect { identity ->
                val switched = identity?.userId != _state.value.identity?.userId
                _state.update { it.copy(identity = identity, confirming = if (switched) false else it.confirming) }
                if (switched) check()
            }
        }
    }

    private fun check() {
        val identity = _state.value.identity ?: return
        checking?.cancel()
        _state.update { it.copy(status = BackupStatus.CHECKING, error = null) }
        checking = viewModelScope.launch {
            val status = try {
                if (backup.isEnabled(identity.userId)) BackupStatus.ON else BackupStatus.OFF
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupStatus.UNAVAILABLE
            }
            _state.update { it.copy(status = status) }
        }
    }

    /** Shows the warning before turning the backup on; refused until the identity's own backup is checked. */
    fun startEnable() {
        val identity = _state.value.identity ?: return
        if (!identity.backupVerified) {
            _state.update { it.copy(error = context.getString(R.string.sec_pkb_backup_first)) }
            return
        }
        _state.update { it.copy(confirming = true, error = null) }
    }

    fun cancel() = _state.update { it.copy(confirming = false) }

    /** Creates the passkey and stores the sealed backup, after the warning was accepted. */
    fun enable() {
        val identity = _state.value.identity ?: return
        _state.update { it.copy(confirming = false, busy = true, error = null) }
        viewModelScope.launch {
            try {
                backup.enable(
                    identity.userId,
                    identity.name,
                    AuthPrompt(context.getString(R.string.sec_pkb_prompt_title), context.getString(R.string.sec_pkb_prompt_subtitle)),
                )
                _state.update { it.copy(busy = false, status = BackupStatus.ON) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.AuthenticationCancelled) {
                _state.update { it.copy(busy = false) }
            } catch (e: PrfUnsupportedException) {
                _state.update { it.copy(busy = false, error = context.getString(R.string.sec_pkb_no_prf)) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.userMessage(context)) }
            }
        }
    }

    /** Deletes the backup. The passkey stays in the password manager, where the user can remove it. */
    fun disable() {
        val identity = _state.value.identity ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                backup.disable(identity.userId)
                _state.update { it.copy(busy = false, status = BackupStatus.OFF) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.userMessage(context)) }
            }
        }
    }
}
