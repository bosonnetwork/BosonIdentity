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
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.call
import io.bosonnetwork.identity.core.designsystem.component.PassphraseProblem
import io.bosonnetwork.identity.core.designsystem.component.passphraseProblem
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
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

/** What the passphrase dialog does. */
enum class PassphraseAction {
    /** Sets the account's first passphrase, then makes its recovery codes. */
    SET,

    /** Replaces the passphrase, given the current one. */
    CHANGE,

    /** Removes the passphrase, given the current one. */
    REMOVE,

    /** Makes new recovery codes, given the passphrase; the old ones stop working. */
    NEW_CODES,
}

/** The passphrase dialog, as typed. */
data class PassphraseForm(
    val action: PassphraseAction,
    /** The passphrase the account has now; [PassphraseAction.SET] has none. */
    val current: String = "",
    /** The new passphrase, for [PassphraseAction.SET] and [PassphraseAction.CHANGE]. */
    val new: String = "",
    val confirm: String = "",
    val busy: Boolean = false,
    val error: String? = null,
) {
    val needsCurrent: Boolean get() = action != PassphraseAction.SET
    val needsNew: Boolean get() = action == PassphraseAction.SET || action == PassphraseAction.CHANGE

    /** Why the new passphrase would be refused, once something is typed; shown as the user types. */
    val newProblem: PassphraseProblem? get() = if (needsNew && new.isNotEmpty()) passphraseProblem(new) else null

    /** The confirmation is typed and differs from the new passphrase. */
    val mismatch: Boolean get() = needsNew && confirm.isNotEmpty() && confirm != new

    val canSubmit: Boolean
        get() = !busy && (!needsCurrent || current.isNotEmpty()) && (!needsNew || (passphraseProblem(new) == null && confirm == new))
}

data class PassphraseUiState(
    val identity: IdentityInfo? = null,
    /** Set while the passphrase dialog is open. */
    val form: PassphraseForm? = null,
    /** New recovery codes, to show once; null once the user saved them. */
    val codes: List<String>? = null,
    /** A one-time message about what was done ("Passphrase changed"). */
    val notice: String? = null,
    /** Set when the user asked for a change before backing up the key; the screen offers the backup. */
    val backupNeeded: Boolean = false,
    /** How many changes were made; the screen asks for the account's security again after each. */
    val changes: Int = 0,
)

/**
 * The account passphrase: setting it, changing it, removing it, and making new recovery codes. Each is
 * an act of authority, confirmed with the user key every time and refused until the key's backup is
 * checked; each but setting a first passphrase also takes the current one. A new passphrase must be
 * one the node accepts ([passphraseProblem]). A first passphrase is followed at once by recovery codes,
 * the way back in if it is forgotten: the user key alone can't reset it.
 */
@HiltViewModel
class PassphraseViewModel @Inject constructor(
    private val activeIdentity: ActiveIdentity,
    private val nodes: NodeConnections,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(PassphraseUiState())
    val state: StateFlow<PassphraseUiState> = _state.asStateFlow()

    private var working: Job? = null

    init {
        viewModelScope.launch {
            activeIdentity.active.collect { identity ->
                val switched = identity?.userId != _state.value.identity?.userId
                if (switched) working?.cancel()
                _state.update {
                    // New recovery codes stay up until saved, even if the user switches meanwhile.
                    if (switched) PassphraseUiState(identity = identity, codes = it.codes, changes = it.changes) else it.copy(identity = identity)
                }
            }
        }
    }

    /** Opens the dialog for [action]; without a checked backup of the key, the screen explains instead. */
    fun start(action: PassphraseAction) {
        val identity = _state.value.identity ?: return
        if (identity.homeNode == null) return
        if (!identity.backupVerified) {
            _state.update { it.copy(backupNeeded = true) }
            return
        }
        _state.update { it.copy(form = PassphraseForm(action), notice = null) }
    }

    fun onCurrentChange(value: String) = updateForm { it.copy(current = value, error = null) }
    fun onNewChange(value: String) = updateForm { it.copy(new = value, error = null) }
    fun onConfirmChange(value: String) = updateForm { it.copy(confirm = value, error = null) }

    fun cancel() {
        working?.cancel()
        _state.update { it.copy(form = null) }
    }

    /** The user saved the recovery codes. */
    fun codesSaved() = _state.update { it.copy(codes = null) }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    fun dismissBackupNeeded() = _state.update { it.copy(backupNeeded = false) }

    /** Does what the dialog is for, after the user confirms with their screen lock. */
    fun submit() {
        val s = _state.value
        val identity = s.identity ?: return
        val node = identity.homeNode ?: return
        val form = s.form ?: return
        if (!form.canSubmit || !identity.backupVerified) return
        val current = form.current
        val new = form.new

        updateForm { it.copy(busy = true, error = null) }
        working = viewModelScope.launch {
            try {
                val (title, subtitle) = when (form.action) {
                    PassphraseAction.SET -> R.string.sec_set_prompt_title to R.string.sec_set_prompt_subtitle
                    PassphraseAction.CHANGE -> R.string.sec_change_prompt_title to R.string.sec_change_prompt_subtitle
                    PassphraseAction.REMOVE -> R.string.sec_remove_prompt_title to R.string.sec_remove_prompt_subtitle
                    PassphraseAction.NEW_CODES -> R.string.sec_codes_prompt_title to R.string.sec_codes_prompt_subtitle
                }
                val prompt = AuthPrompt(context.getString(title), context.getString(subtitle, node.hostName()))
                // For a first passphrase: the codes, or why they couldn't be made (the passphrase is set either way).
                var codesFailure: Exception? = null
                val codes = nodes.asUser(identity.userId, node, prompt) { client, _ ->
                    when (form.action) {
                        PassphraseAction.SET -> {
                            call { client.setPassphrase(new).await() }
                            try {
                                call { client.makeRecoveryCodes(new).await() }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                codesFailure = e
                                null
                            }
                        }
                        PassphraseAction.CHANGE -> {
                            call { client.updatePassphrase(current, new).await() }
                            null
                        }
                        PassphraseAction.REMOVE -> {
                            call { client.clearPassphrase(current).await() }
                            null
                        }
                        PassphraseAction.NEW_CODES -> call { client.makeRecoveryCodes(current).await() }
                    }
                }
                val notice = when (form.action) {
                    PassphraseAction.SET -> codesFailure?.let {
                        context.getString(R.string.sec_codes_after_set_failed, it.userMessage(context))
                    } ?: context.getString(R.string.sec_passphrase_set_done)
                    PassphraseAction.CHANGE -> context.getString(R.string.sec_passphrase_changed_done)
                    PassphraseAction.REMOVE -> context.getString(R.string.sec_passphrase_removed_done)
                    PassphraseAction.NEW_CODES -> null
                }
                _state.update { it.copy(form = null, codes = codes, notice = notice, changes = it.changes + 1) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.PassphraseRequired) {
                if (form.action == PassphraseAction.SET) {
                    // The account has a passphrase after all (set elsewhere): changing it needs the current one.
                    updateForm {
                        it.copy(action = PassphraseAction.CHANGE, busy = false, error = context.getString(R.string.sec_passphrase_already_set))
                    }
                } else {
                    updateForm { it.copy(busy = false, error = e.userMessage(context)) }
                }
            } catch (e: AppError.Forbidden) {
                val message = if (form.needsCurrent) context.getString(R.string.sec_wrong_passphrase) else e.userMessage(context)
                updateForm { it.copy(busy = false, current = "", error = message) }
            } catch (e: AppError.Conflict) {
                // Recovery codes for an account without a passphrase.
                val message = if (form.action == PassphraseAction.NEW_CODES) context.getString(R.string.sec_codes_no_passphrase) else e.userMessage(context)
                updateForm { it.copy(busy = false, error = message) }
            } catch (e: Exception) {
                updateForm { it.copy(busy = false, error = e.userMessage(context)) }
            }
        }
    }

    private fun updateForm(transform: (PassphraseForm) -> PassphraseForm) =
        _state.update { s -> s.form?.let { s.copy(form = transform(it)) } ?: s }
}
