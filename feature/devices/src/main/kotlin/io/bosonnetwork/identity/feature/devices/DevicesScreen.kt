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

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.Identicon
import io.bosonnetwork.identity.core.designsystem.component.RefreshBox
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.designsystem.component.Status
import io.bosonnetwork.identity.core.designsystem.component.StatusLine
import io.bosonnetwork.identity.core.designsystem.theme.brand

/**
 * The devices of the identity in use: this phone, the apps and the passkeys that act for it. A device
 * opens its details, where it can be renamed or removed. [onApprove] opens approving a new device;
 * [onBackup] the key's backup, which approving and removing need first; [onJoinNode] joining a node.
 * When [changed] is set (a device was approved meanwhile), the list is asked for again and
 * [onChangeSeen] runs. [navigationIcon] goes at the start of the top bar; the app puts its identity
 * switcher there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onApprove: (userId: String) -> Unit,
    onBackup: (userId: String) -> Unit,
    onJoinNode: (userId: String) -> Unit,
    changed: Boolean = false,
    onChangeSeen: () -> Unit = {},
    navigationIcon: @Composable () -> Unit = {},
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val identity = state.identity
    LaunchedEffect(changed) {
        if (changed) {
            viewModel.refresh()
            onChangeSeen()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dev_title)) },
                navigationIcon = navigationIcon,
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.dev_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (identity?.homeNode != null) {
                ExtendedFloatingActionButton(
                    onClick = { onApprove(identity.userId) },
                    icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                    text = { Text(stringResource(R.string.dev_approve_fab)) },
                )
            }
        },
    ) { padding ->
        if (identity == null) return@Scaffold
        RefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val width = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                when (val devices = state.devices) {
                    DevicesState.None -> item {
                        SectionCard(width) {
                            Text(
                                stringResource(R.string.dev_no_node),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                            Button(onClick = { onJoinNode(identity.userId) }, modifier = Modifier.padding(start = 16.dp, bottom = 16.dp)) {
                                Text(stringResource(R.string.dev_join))
                            }
                        }
                    }
                    DevicesState.Loading -> item {
                        Box(width.padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    DevicesState.Locked -> item {
                        SectionCard(width) {
                            StatusLine(Status.ATTENTION, stringResource(R.string.dev_locked), stringResource(R.string.dev_locked_detail), viewModel::refresh)
                        }
                    }
                    is DevicesState.Failed -> item {
                        SectionCard(width) {
                            StatusLine(Status.PROBLEM, stringResource(R.string.dev_failed), devices.message, viewModel::refresh)
                        }
                    }
                    is DevicesState.Ready -> {
                        val groups = devices.groups
                        item { Group(width, stringResource(R.string.dev_group_this_phone)) {
                            if (groups.thisPhone != null) {
                                DeviceRow(groups.thisPhone) { selectedId = groups.thisPhone.id }
                            } else {
                                Note(stringResource(R.string.dev_this_phone_missing))
                            }
                        } }
                        if (groups.apps.isNotEmpty()) {
                            item { Group(width, stringResource(R.string.dev_group_apps)) {
                                groups.apps.forEach { device -> DeviceRow(device) { selectedId = device.id } }
                            } }
                        }
                        if (groups.passkeys.isNotEmpty()) {
                            item { Group(width, stringResource(R.string.dev_group_passkeys)) {
                                groups.passkeys.forEach { device -> DeviceRow(device) { selectedId = device.id } }
                            } }
                        }
                        if (groups.apps.isEmpty() && groups.passkeys.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.dev_no_others),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = width,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val selected = selectedId?.let { id -> (state.devices as? DevicesState.Ready)?.groups?.find(id) }
    if (selected != null) {
        DeviceSheet(
            device = selected,
            onRename = {
                selectedId = null
                viewModel.startRename(selected)
            },
            onRemove = {
                selectedId = null
                viewModel.startRemove(selected)
            },
            onDismiss = { selectedId = null },
        )
    }
    state.rename?.let { rename ->
        RenameDialog(rename, onChange = viewModel::onRenameChange, onSave = viewModel::rename, onCancel = viewModel::cancelRename)
    }
    state.removal?.let { removal ->
        RemoveDialog(
            removal,
            onPassphraseChange = viewModel::onRemovePassphraseChange,
            onRemove = viewModel::remove,
            onCancel = viewModel::cancelRemove,
        )
    }
    if (state.backupNeeded && identity != null) {
        BackupNeededDialog(
            onBackup = {
                viewModel.dismissBackupNeeded()
                onBackup(identity.userId)
            },
            onDismiss = viewModel::dismissBackupNeeded,
        )
    }
}

@Composable
private fun Group(modifier: Modifier, title: String, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        SectionCard { content() }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun DeviceRow(device: DeviceView, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
        leadingContent = { Icon(device.icon(), contentDescription = null) },
        headlineContent = { Text(device.displayName()) },
        supportingContent = {
            Column {
                if (device.app.isNotBlank() && device.name.isNotBlank()) Text(device.app)
                Text(if (device.thisPhone) stringResource(R.string.dev_in_use_now) else lastSeenText(device.lastSeen))
            }
        },
        trailingContent = if (device.admin || device.recoveryMethod) {
            {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (device.admin) AdminBadge()
                    if (device.recoveryMethod) RecoveryBadge()
                }
            }
        } else {
            null
        },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun DeviceView.displayName(): String =
    name.ifBlank { app }.ifBlank { stringResource(R.string.dev_unnamed) }

private fun DeviceView.icon(): ImageVector = when {
    thisPhone -> Icons.Filled.PhoneAndroid
    isPasskey -> Icons.Filled.Key
    else -> Icons.Filled.Apps
}

@Composable
private fun lastSeenText(lastSeen: Long): String {
    if (lastSeen <= 0) return stringResource(R.string.dev_never_seen)
    val now = System.currentTimeMillis()
    // The node's clock may be ahead of this phone's: never say "in 2 minutes".
    val relative = DateUtils.getRelativeTimeSpanString(minOf(lastSeen, now), now, DateUtils.MINUTE_IN_MILLIS).toString()
    return stringResource(R.string.dev_last_seen, relative)
}

/** Admin is marked in the brand's violet everywhere it appears. */
@Composable
internal fun AdminBadge() = Badge(
    stringResource(R.string.dev_badge_admin),
    MaterialTheme.colorScheme.tertiaryContainer,
    MaterialTheme.colorScheme.onTertiaryContainer,
)

@Composable
private fun RecoveryBadge() = Badge(
    stringResource(R.string.dev_badge_recovery),
    MaterialTheme.colorScheme.secondaryContainer,
    MaterialTheme.colorScheme.onSecondaryContainer,
)

@Composable
private fun Badge(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/**
 * Ignores touches while another app's window covers this one, so an overlay can't trick the user
 * into tapping an approval (tapjacking). Every screen and dialog that confirms an act of authority
 * uses it.
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(device: DeviceView, onRename: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.brand.card) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Identicon(device.id, size = 48.dp)
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(device.displayName(), style = MaterialTheme.typography.titleLarge)
                    IdText(device.id, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (device.admin || device.recoveryMethod) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (device.admin) AdminBadge()
                    if (device.recoveryMethod) RecoveryBadge()
                }
            }
            val unknown = stringResource(R.string.dev_detail_unknown)
            Field(
                stringResource(R.string.dev_detail_kind),
                stringResource(if (device.isPasskey) R.string.dev_kind_passkey else R.string.dev_kind_app),
            )
            Field(stringResource(R.string.dev_detail_app), device.app.ifBlank { unknown })
            Field(
                stringResource(R.string.dev_detail_added),
                if (device.createdAt > 0) {
                    DateUtils.formatDateTime(context, device.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
                } else {
                    unknown
                },
            )
            Field(
                stringResource(R.string.dev_detail_last_seen),
                if (device.thisPhone) stringResource(R.string.dev_in_use_now) else lastSeenText(device.lastSeen),
            )
            Field(stringResource(R.string.dev_detail_last_address), device.lastAddress ?: unknown)
            Column {
                Text(stringResource(R.string.dev_detail_id), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IdText(device.id, style = MaterialTheme.typography.bodyMedium)
            }
            if (device.admin) SheetNote(stringResource(R.string.dev_detail_admin))
            if (device.recoveryMethod) SheetNote(stringResource(R.string.dev_detail_recovery))
            if (device.thisPhone) SheetNote(stringResource(R.string.dev_detail_this_phone))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)) {
                OutlinedButton(onClick = onRename) { Text(stringResource(R.string.dev_rename)) }
                if (!device.thisPhone) {
                    TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.dev_remove))
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SheetNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RenameDialog(rename: Rename, onChange: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!rename.busy) onCancel() },
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.dev_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rename.name,
                    onValueChange = onChange,
                    label = { Text(stringResource(R.string.dev_rename_label)) },
                    supportingText = { Text(stringResource(R.string.dev_rename_hint)) },
                    isError = rename.name.isNotEmpty() && rename.validName == null,
                    singleLine = true,
                    enabled = !rename.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                rename.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (rename.busy) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                TextButton(onClick = onSave, enabled = rename.validName != null) { Text(stringResource(R.string.dev_save)) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !rename.busy) { Text(stringResource(R.string.dev_cancel)) } },
    )
}

@Composable
private fun RemoveDialog(removal: DeviceRemoval, onPassphraseChange: (String) -> Unit, onRemove: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!removal.busy) onCancel() },
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.dev_remove_title)) },
        text = {
            ObscuredTouchGuard()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Identicon(removal.device.id, size = 40.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(removal.device.displayName(), style = MaterialTheme.typography.titleSmall)
                        IdText(removal.device.id, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(stringResource(R.string.dev_remove_body, removal.device.displayName()))
                if (removal.needsPassphrase) {
                    Text(stringResource(R.string.dev_remove_passphrase))
                    OutlinedTextField(
                        value = removal.passphrase,
                        onValueChange = onPassphraseChange,
                        label = { Text(stringResource(R.string.dev_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        singleLine = true,
                        enabled = !removal.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                removal.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (removal.busy) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                TextButton(
                    onClick = onRemove,
                    enabled = !removal.needsPassphrase || removal.passphrase.isNotEmpty(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.dev_remove)) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !removal.busy) { Text(stringResource(R.string.dev_cancel)) } },
    )
}

@Composable
internal fun BackupNeededDialog(onBackup: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.dev_backup_title)) },
        text = { Text(stringResource(R.string.dev_backup_body)) },
        confirmButton = { TextButton(onClick = onBackup) { Text(stringResource(R.string.dev_backup_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dev_cancel)) } },
    )
}
