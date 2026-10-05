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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.bosonnetwork.identity.core.designsystem.component.PassphraseProblem
import io.bosonnetwork.identity.core.designsystem.component.text
import io.bosonnetwork.identity.core.designsystem.theme.brand

/**
 * Ignores touches while another app's window covers this one, so an overlay can't trick the user
 * into tapping a confirmation (tapjacking). Every dialog that confirms an act of authority uses it.
 */
@Composable
internal fun ObscuredTouchGuard() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.filterTouchesWhenObscured
        view.filterTouchesWhenObscured = true
        onDispose { view.filterTouchesWhenObscured = before }
    }
}

/** A passphrase field: hidden, no suggestions, no autocorrect. */
@Composable
internal fun PassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A new passphrase and its confirmation, with the reason a weak one would be refused shown as the
 * user types, and the rule itself before that.
 */
@Composable
internal fun NewPassphraseFields(
    new: String,
    confirm: String,
    problem: PassphraseProblem?,
    mismatch: Boolean,
    enabled: Boolean,
    onNewChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
) {
    PassphraseField(
        new,
        onNewChange,
        stringResource(R.string.sec_new_passphrase),
        enabled = enabled,
        isError = problem != null,
        supportingText = problem?.text() ?: stringResource(R.string.sec_new_passphrase_rule),
    )
    PassphraseField(
        confirm,
        onConfirmChange,
        stringResource(R.string.sec_confirm_passphrase),
        enabled = enabled,
        isError = mismatch,
        supportingText = if (mismatch) stringResource(R.string.sec_passphrase_mismatch) else null,
    )
}

@Composable
internal fun ErrorText(error: String?) {
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun Busy() {
    CircularProgressIndicator(Modifier.size(24.dp))
}

@Composable
internal fun PassphraseDialog(
    form: PassphraseForm,
    onCurrentChange: (String) -> Unit,
    onNewChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val (title, body, action) = when (form.action) {
        PassphraseAction.SET -> Triple(R.string.sec_set_title, R.string.sec_set_body, R.string.sec_set_action)
        PassphraseAction.CHANGE -> Triple(R.string.sec_change_title, R.string.sec_change_body, R.string.sec_change_action)
        PassphraseAction.REMOVE -> Triple(R.string.sec_remove_title, R.string.sec_remove_body, R.string.sec_remove_action)
        PassphraseAction.NEW_CODES -> Triple(R.string.sec_codes_title, R.string.sec_codes_body, R.string.sec_codes_action)
    }
    AlertDialog(
        onDismissRequest = { if (!form.busy) onCancel() },
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(title)) },
        text = {
            ObscuredTouchGuard()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(body))
                if (form.needsCurrent) {
                    PassphraseField(
                        form.current,
                        onCurrentChange,
                        stringResource(if (form.needsNew) R.string.sec_current_passphrase else R.string.sec_passphrase),
                        enabled = !form.busy,
                    )
                }
                if (form.needsNew) {
                    NewPassphraseFields(form.new, form.confirm, form.newProblem, form.mismatch, !form.busy, onNewChange, onConfirmChange)
                }
                if (form.action == PassphraseAction.SET) {
                    Text(stringResource(R.string.sec_set_codes_next), style = MaterialTheme.typography.bodySmall)
                }
                ErrorText(form.error)
            }
        },
        confirmButton = {
            if (form.busy) {
                Busy()
            } else {
                TextButton(
                    onClick = onSubmit,
                    enabled = form.canSubmit,
                    colors = if (form.action == PassphraseAction.REMOVE) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                ) { Text(stringResource(action)) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !form.busy) { Text(stringResource(R.string.sec_cancel)) } },
    )
}

@Composable
internal fun UnlinkDialog(unlink: Unlink, onPassphraseChange: (String) -> Unit, onUnlink: () -> Unit, onCancel: () -> Unit) {
    val provider = unlink.account.provider ?: stringResource(R.string.sec_linked_unknown_provider)
    AlertDialog(
        onDismissRequest = { if (!unlink.busy) onCancel() },
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.sec_unlink_title, provider)) },
        text = {
            ObscuredTouchGuard()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull(unlink.account.name, unlink.account.email).joinToString(" - ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall)
                }
                Text(stringResource(R.string.sec_unlink_body))
                if (unlink.needsPassphrase) {
                    Text(stringResource(R.string.sec_unlink_passphrase))
                    PassphraseField(unlink.passphrase, onPassphraseChange, stringResource(R.string.sec_passphrase), enabled = !unlink.busy)
                }
                ErrorText(unlink.error)
            }
        },
        confirmButton = {
            if (unlink.busy) {
                Busy()
            } else {
                TextButton(
                    onClick = onUnlink,
                    enabled = !unlink.needsPassphrase || unlink.passphrase.isNotEmpty(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.sec_unlink)) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !unlink.busy) { Text(stringResource(R.string.sec_cancel)) } },
    )
}

@Composable
internal fun RenewDialog(renewal: Renewal, onPassphraseChange: (String) -> Unit, onRenew: () -> Unit, onClose: () -> Unit) {
    val working = renewal.step == RenewStep.WORKING
    // Once the node took the new key, closing loses it: only the buttons close the dialog then.
    val holdsKey = renewal.step == RenewStep.SAVE_FAILED
    AlertDialog(
        onDismissRequest = { if (!working && !holdsKey) onClose() },
        properties = DialogProperties(dismissOnBackPress = !holdsKey, dismissOnClickOutside = !holdsKey),
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(if (renewal.step == RenewStep.DONE) R.string.sec_renew_done_title else R.string.sec_renew_title)) },
        text = {
            ObscuredTouchGuard()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (renewal.step) {
                    RenewStep.CONFIRM -> Text(stringResource(R.string.sec_renew_body))
                    RenewStep.PASSPHRASE -> {
                        Text(stringResource(R.string.sec_renew_passphrase))
                        PassphraseField(renewal.passphrase, onPassphraseChange, stringResource(R.string.sec_passphrase))
                    }
                    RenewStep.WORKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Busy()
                        Text(stringResource(R.string.sec_working), Modifier.padding(start = 12.dp))
                    }
                    RenewStep.SAVE_FAILED -> {
                        Text(stringResource(R.string.sec_renew_save_failed))
                        Text(stringResource(R.string.sec_renew_save_failed_cancel), style = MaterialTheme.typography.bodySmall)
                    }
                    RenewStep.DONE -> {
                        Text(stringResource(R.string.sec_renew_done_body))
                        if (renewal.oldKeyKept) Text(stringResource(R.string.sec_renew_old_kept), color = MaterialTheme.colorScheme.error)
                    }
                }
                ErrorText(renewal.error)
            }
        },
        confirmButton = {
            when (renewal.step) {
                RenewStep.CONFIRM -> TextButton(onClick = onRenew) { Text(stringResource(R.string.sec_renew_action)) }
                RenewStep.PASSPHRASE -> TextButton(onClick = onRenew, enabled = renewal.passphrase.isNotEmpty()) {
                    Text(stringResource(R.string.sec_renew_action))
                }
                RenewStep.WORKING -> Unit
                RenewStep.SAVE_FAILED -> TextButton(onClick = onRenew) { Text(stringResource(R.string.sec_try_again)) }
                RenewStep.DONE -> TextButton(onClick = onClose) { Text(stringResource(R.string.sec_done)) }
            }
        },
        dismissButton = if (renewal.step != RenewStep.DONE && !working) {
            { TextButton(onClick = onClose) { Text(stringResource(R.string.sec_cancel)) } }
        } else {
            null
        },
    )
}

@Composable
internal fun MoveDialog(host: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.sec_move_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sec_move_step1))
                Text(
                    host?.let { stringResource(R.string.sec_move_step2_node, it) } ?: stringResource(R.string.sec_move_step2),
                )
                Text(stringResource(R.string.sec_move_step3))
                Text(stringResource(R.string.sec_move_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sec_done)) } },
    )
}

@Composable
internal fun BackupNeededDialog(onBackup: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.sec_backup_title)) },
        text = { Text(stringResource(R.string.sec_backup_body)) },
        confirmButton = { TextButton(onClick = onBackup) { Text(stringResource(R.string.sec_backup_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sec_cancel)) } },
    )
}

/** The way back in, step by step; while a reset grant is held, only Cancel closes it. */
@Composable
internal fun ForgotPassphraseDialog(state: ForgotUiState, viewModel: ForgotPassphraseViewModel) {
    val holdsGrant = state.holdsGrant
    AlertDialog(
        onDismissRequest = { if (!holdsGrant && !state.busy) viewModel.close() },
        properties = DialogProperties(dismissOnBackPress = !holdsGrant, dismissOnClickOutside = !holdsGrant),
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(if (state.step == ForgotStep.DONE) R.string.sec_forgot_done_title else R.string.sec_forgot_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.step) {
                    ForgotStep.CLOSED -> Unit
                    ForgotStep.LOADING -> Busy()
                    ForgotStep.CHOOSE -> ForgotChoices(state, viewModel)
                    ForgotStep.CODE -> {
                        Text(stringResource(R.string.sec_forgot_code_body))
                        OutlinedTextField(
                            value = state.code,
                            onValueChange = viewModel::onCodeChange,
                            label = { Text(stringResource(R.string.sec_forgot_code_label)) },
                            placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
                            singleLine = true,
                            enabled = !state.busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    ForgotStep.WAITING -> {
                        Busy()
                        Text(stringResource(R.string.sec_browser_waiting))
                    }
                    ForgotStep.NEW_PASSPHRASE -> {
                        Text(stringResource(R.string.sec_forgot_new_body))
                        NewPassphraseFields(state.new, state.confirm, state.newProblem, state.mismatch, !state.busy, viewModel::onNewChange, viewModel::onConfirmChange)
                    }
                    ForgotStep.DONE -> Text(stringResource(R.string.sec_forgot_done_body))
                }
                ErrorText(state.error)
            }
        },
        confirmButton = {
            when {
                state.busy -> Busy()
                state.step == ForgotStep.CODE -> TextButton(onClick = viewModel::submitCode, enabled = state.code.isNotBlank()) {
                    Text(stringResource(R.string.sec_continue))
                }
                state.step == ForgotStep.NEW_PASSPHRASE -> TextButton(onClick = viewModel::reset, enabled = state.canReset) {
                    Text(stringResource(R.string.sec_forgot_reset_action))
                }
                state.step == ForgotStep.DONE -> TextButton(onClick = viewModel::close) { Text(stringResource(R.string.sec_done)) }
                else -> Unit
            }
        },
        dismissButton = {
            when (state.step) {
                ForgotStep.CODE, ForgotStep.WAITING -> TextButton(onClick = viewModel::backToChoose, enabled = !state.busy) {
                    Text(stringResource(R.string.sec_back))
                }
                ForgotStep.DONE -> Unit
                else -> TextButton(onClick = viewModel::close, enabled = !state.busy) { Text(stringResource(R.string.sec_cancel)) }
            }
        },
    )
}

@Composable
private fun ForgotChoices(state: ForgotUiState, viewModel: ForgotPassphraseViewModel) {
    val methods = state.methods ?: return
    if (!methods.any) {
        Text(stringResource(R.string.sec_forgot_no_method))
        return
    }
    Text(stringResource(R.string.sec_forgot_choose_body))
    if (methods.recoveryCodes > 0) {
        Choice(stringResource(R.string.sec_forgot_use_code), onClick = viewModel::useCode)
    }
    if (methods.accounts > 0) {
        if (state.providers.isEmpty()) {
            Text(stringResource(R.string.sec_forgot_no_providers), style = MaterialTheme.typography.bodySmall)
        }
        state.providers.forEach { provider ->
            Choice(stringResource(R.string.sec_forgot_use_account, provider.name), onClick = { viewModel.useAccount(provider) })
        }
    }
    if (methods.passkeys > 0) {
        Choice(stringResource(R.string.sec_forgot_use_passkey), onClick = viewModel::usePasskey)
        Text(stringResource(R.string.sec_forgot_passkey_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun Choice(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

/** Linking an account: choose the provider, sign in there, come back, confirm. */
@Composable
internal fun LinkAccountDialog(state: LinkUiState, host: String, viewModel: LinkAccountViewModel) {
    val working = state.step == LinkStep.WORKING || state.step == LinkStep.LOADING
    val provider = state.provider?.name.orEmpty()
    AlertDialog(
        onDismissRequest = { if (!working) viewModel.close() },
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(if (state.step == LinkStep.DONE) R.string.sec_link_done_title else R.string.sec_link_title)) },
        text = {
            ObscuredTouchGuard()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.step) {
                    LinkStep.CLOSED -> Unit
                    LinkStep.LOADING, LinkStep.WORKING -> Busy()
                    LinkStep.CHOOSE -> {
                        Text(stringResource(R.string.sec_link_body))
                        state.providers.forEach { p -> Choice(p.name, onClick = { viewModel.choose(p) }) }
                    }
                    LinkStep.WAITING -> {
                        Busy()
                        Text(stringResource(R.string.sec_browser_waiting))
                    }
                    LinkStep.CONFIRM -> {
                        Text(stringResource(R.string.sec_link_confirm, provider, host))
                        if (state.needsPassphrase) {
                            Text(stringResource(R.string.sec_link_passphrase))
                            PassphraseField(state.passphrase, viewModel::onPassphraseChange, stringResource(R.string.sec_passphrase))
                        }
                    }
                    LinkStep.DONE -> Text(stringResource(R.string.sec_link_done_body, provider))
                }
                ErrorText(state.error)
            }
        },
        confirmButton = {
            when (state.step) {
                LinkStep.CONFIRM -> TextButton(onClick = viewModel::link, enabled = !state.needsPassphrase || state.passphrase.isNotEmpty()) {
                    Text(stringResource(R.string.sec_link_confirm_action))
                }
                LinkStep.DONE -> TextButton(onClick = viewModel::close) { Text(stringResource(R.string.sec_done)) }
                else -> Unit
            }
        },
        dismissButton = {
            when (state.step) {
                LinkStep.WAITING, LinkStep.CONFIRM -> TextButton(onClick = viewModel::backToChoose) { Text(stringResource(R.string.sec_back)) }
                LinkStep.DONE -> Unit
                else -> TextButton(onClick = viewModel::close, enabled = !working) { Text(stringResource(R.string.sec_cancel)) }
            }
        },
    )
}
