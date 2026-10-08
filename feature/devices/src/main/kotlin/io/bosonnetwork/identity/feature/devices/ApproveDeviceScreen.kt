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

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.director.client.Device
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.Identicon
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.designsystem.component.shortId
import io.bosonnetwork.identity.core.designsystem.theme.brand
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.qr.QrScanner
import java.util.Locale

/**
 * Approving a new device: scanning or pasting the pairing code it shows, reviewing who asks, from
 * where and for what, and approving or denying it. Every approval reads as a sentence: who (the
 * identity), what (the device, and whether it gets the identity's key), where (the node). [onDone]
 * runs when the device joined; [onClose] when the user leaves otherwise; [onBackup] opens the key's
 * backup, which approving needs first. As the app's scanner, it hands a web page's sign-in code to
 * [onSignIn] and a node console's enrollment code to [onEnroll].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApproveDeviceScreen(
    onDone: () -> Unit,
    onClose: () -> Unit,
    onBackup: (userId: String) -> Unit,
    onSignIn: (requestId: String) -> Unit = {},
    onEnroll: (code: String) -> Unit = {},
    viewModel: ApproveDeviceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.signInRequestId) {
        state.signInRequestId?.let {
            viewModel.signInHandled()
            onSignIn(it)
        }
    }
    LaunchedEffect(state.enrollCode) {
        state.enrollCode?.let {
            viewModel.signInHandled()
            onEnroll(it)
        }
    }
    ObscuredTouchGuard()
    BackHandler(enabled = state.step == ApproveStep.PASSPHRASE) { viewModel.backToReview() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    // As the app's scanner, the screen is named for the scan until it knows what was scanned.
                    val scanning = state.general && state.step == ApproveStep.SCAN
                    Text(stringResource(if (scanning) R.string.dev_scan_title else R.string.dev_approve_title))
                },
                navigationIcon = {
                    IconButton(onClick = if (state.step == ApproveStep.DONE) onDone else onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dev_close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state.step) {
                    ApproveStep.SCAN -> ScanStep(state, viewModel)
                    ApproveStep.LOADING, ApproveStep.WORKING -> {
                        Spacer(Modifier.height(48.dp))
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        StepText(
                            stringResource(if (state.step == ApproveStep.LOADING) R.string.dev_approve_loading else R.string.dev_approve_working),
                        )
                    }
                    ApproveStep.REVIEW -> ReviewStep(state, viewModel, onBackup = { state.identity?.let { onBackup(it.userId) } })
                    ApproveStep.PASSPHRASE -> PassphraseStep(state, viewModel)
                    ApproveStep.DONE -> Outcome(
                        title = stringResource(R.string.dev_approve_done, state.pending?.displayName() ?: stringResource(R.string.dev_approve_unnamed)),
                        body = stringResource(R.string.dev_approve_done_body),
                        success = true,
                    ) {
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_done)) }
                    }
                    ApproveStep.DENIED -> Outcome(
                        title = stringResource(R.string.dev_approve_denied),
                        body = stringResource(R.string.dev_approve_denied_body),
                    ) {
                        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_done)) }
                    }
                    ApproveStep.EXPIRED -> Outcome(
                        title = stringResource(R.string.dev_approve_gone),
                        body = stringResource(R.string.dev_approve_gone_body),
                    ) {
                        Button(onClick = viewModel::scanAgain, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.dev_approve_scan_another))
                        }
                        TextButton(onClick = onClose) { Text(stringResource(R.string.dev_close)) }
                    }
                    ApproveStep.ERROR -> Outcome(
                        title = stringResource(R.string.dev_approve_error_title),
                        body = state.error.orEmpty(),
                    ) {
                        Button(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_approve_retry)) }
                        TextButton(onClick = viewModel::scanAgain) { Text(stringResource(R.string.dev_approve_scan_another)) }
                    }
                }
                if (state.step != ApproveStep.ERROR) {
                    state.error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanStep(state: ApproveUiState, viewModel: ApproveDeviceViewModel) {
    val context = LocalContext.current
    var cameraOn by rememberSaveable {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraOn = true
    }
    StepText(stringResource(if (state.general) R.string.dev_scan_body else R.string.dev_approve_scan_body))
    if (cameraOn) {
        QrScanner(onScanned = viewModel::onScanned, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
    } else {
        OutlinedButton(
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    cameraOn = true
                } else {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.dev_approve_camera)) }
    }
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = state.pasted,
        onValueChange = viewModel::onPastedChange,
        label = { Text(stringResource(if (state.general) R.string.dev_scan_paste_label else R.string.dev_approve_paste_label)) },
        placeholder = { Text(stringResource(if (state.general) R.string.dev_scan_paste_hint else R.string.dev_approve_paste_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = viewModel::submitPasted, enabled = state.pasted.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dev_approve_continue))
    }
}

@Composable
private fun ReviewStep(state: ApproveUiState, viewModel: ApproveDeviceViewModel, onBackup: () -> Unit) {
    val pending = state.pending ?: return
    val identity = state.identity
    val host = identity?.homeNode?.hostName().orEmpty()
    Text(stringResource(R.string.dev_approve_review_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))

    // What: the device.
    SectionCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Identicon(pending.deviceId, size = 48.dp)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(pending.displayName(), style = MaterialTheme.typography.titleMedium)
                if (pending.app.isNotBlank() && pending.name.isNotBlank()) Text(pending.app, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(if (pending.kind == Device.KIND_PASSKEY) R.string.dev_kind_passkey else R.string.dev_kind_app),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IdText(pending.deviceId, style = MaterialTheme.typography.bodySmall)
            }
        }
        pending.requestedFrom?.let {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                Text(stringResource(R.string.dev_approve_from, it), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.dev_approve_from_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    // Who and where: the identity, at its node.
    if (identity != null) WhoWhere(identity, host)
    Spacer(Modifier.height(12.dp))

    if (state.expired) {
        Text(stringResource(R.string.dev_approve_expired), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    } else {
        Text(
            stringResource(R.string.dev_approve_expires_in, countdown(state.remainingSeconds)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))

    if (pending.wantsUserKey) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Text(
                        stringResource(R.string.dev_approve_wants_key_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.dev_approve_wants_key), style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp)
                        .toggleable(value = state.understood, role = Role.Checkbox, enabled = !state.expired, onValueChange = viewModel::onUnderstoodChange),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = state.understood, onCheckedChange = null, enabled = !state.expired)
                    Text(stringResource(R.string.dev_approve_understand), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    } else {
        Text(stringResource(R.string.dev_approve_own_key), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }

    if (state.canAdmin) {
        Spacer(Modifier.height(12.dp))
        AdminOption(host, state.admin, enabled = !state.expired, onChange = viewModel::onAdminChange)
    }
    Spacer(Modifier.height(20.dp))

    when {
        state.expired -> Button(onClick = viewModel::scanAgain, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dev_approve_scan_another))
        }
        identity?.backupVerified != true -> {
            // An identity without a checked backup authorizes nothing; denying is still possible.
            SectionCard {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.dev_backup_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.dev_backup_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onBackup) { Text(stringResource(R.string.dev_backup_action)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = viewModel::deny, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_approve_deny)) }
        }
        else -> {
            Button(onClick = viewModel::approve, enabled = state.canApprove, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dev_approve_action))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = viewModel::deny, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_approve_deny)) }
        }
    }
}

@Composable
internal fun WhoWhere(identity: IdentityInfo, host: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Identicon(identity.userId, size = 40.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(stringResource(R.string.dev_approve_who), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(identity.name ?: shortId(identity.userId), style = MaterialTheme.typography.titleSmall)
            IdText(identity.userId, style = MaterialTheme.typography.bodySmall)
            if (host.isNotEmpty()) {
                Text(stringResource(R.string.dev_approve_where, host), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The administrator option, in the brand's violet like everything administrative. */
@Composable
private fun AdminOption(host: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, enabled = enabled, onValueChange = onChange).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dev_approve_admin_option, host), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.dev_approve_admin_detail), style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.tertiary,
                    checkedThumbColor = MaterialTheme.colorScheme.onTertiary,
                ),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun PassphraseStep(state: ApproveUiState, viewModel: ApproveDeviceViewModel) {
    Text(stringResource(R.string.dev_approve_passphrase_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    StepText(stringResource(R.string.dev_approve_passphrase_body))
    OutlinedTextField(
        value = state.passphrase,
        onValueChange = viewModel::onPassphraseChange,
        label = { Text(stringResource(R.string.dev_passphrase)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = viewModel::approve, enabled = state.passphrase.isNotEmpty() && state.canApprove, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dev_approve_action))
    }
    TextButton(onClick = viewModel::backToReview) { Text(stringResource(R.string.dev_cancel)) }
}

@Composable
internal fun Outcome(title: String, body: String, success: Boolean = false, actions: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(32.dp))
    if (success) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.brand.success, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
    }
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    if (body.isNotEmpty()) StepText(body)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
}

@Composable
internal fun StepText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun PendingView.displayName(): String =
    name.ifBlank { app }.ifBlank { stringResource(R.string.dev_approve_unnamed) }

/** "4:59": minutes and seconds left. */
internal fun countdown(seconds: Long): String = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
