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

package io.bosonnetwork.identity.feature.account

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.Identicon
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.designsystem.component.Status
import io.bosonnetwork.identity.core.designsystem.component.StatusLine
import io.bosonnetwork.identity.core.designsystem.theme.brand
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.qr.rememberQrBitmap

/**
 * The account of the identity in use: its profile at the home node, the node, the plan, removing
 * the identity from this phone, and about the app. [onJoinNode] opens joining a node;
 * [onNoIdentityLeft] runs when the last identity was removed; [onAdminOpened] runs when admin mode
 * opened for the home node, to show the Admin tab. [navigationIcon] goes at the start of the top bar;
 * the app puts its identity switcher there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onJoinNode: (userId: String) -> Unit,
    onNoIdentityLeft: () -> Unit,
    onAdminOpened: () -> Unit = {},
    navigationIcon: @Composable () -> Unit = {},
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showNodeQr by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val identity = state.identity
    LaunchedEffect(state.noIdentityLeft) { if (state.noIdentityLeft) onNoIdentityLeft() }
    LaunchedEffect(state.adminOpened) {
        if (state.adminOpened) {
            viewModel.adminOpenedSeen()
            onAdminOpened()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.acc_title)) },
                navigationIcon = navigationIcon,
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.acc_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (identity == null) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProfileSection(identity, state.profile, onEdit = viewModel::startEdit, onRetry = viewModel::refresh)
                NodeSection(identity.homeNode, onShowQr = { showNodeQr = true }, onJoin = { onJoinNode(identity.userId) })
                (state.profile as? ProfileState.Ready)?.let { PlanSection(it.view.plan) }
                identity.homeNode?.let { AdminSection(it, state.admin, onStart = viewModel::startAdmin) }
                PhoneSection(state.removeError, onRemove = { confirmRemove = true })
                AboutSection()
            }
        }
    }

    val node = identity?.homeNode
    if (showNodeQr && node != null) {
        NodeQrDialog(node, onDismiss = { showNodeQr = false })
    }
    state.edit?.let { edit ->
        EditProfileDialog(
            edit = edit,
            onNameChange = viewModel::onNameChange,
            onBioChange = viewModel::onBioChange,
            onEmailChange = viewModel::onEmailChange,
            onPassphraseChange = viewModel::onPassphraseChange,
            onSave = viewModel::save,
            onCancel = viewModel::cancelEdit,
        )
    }
    if ((confirmRemove || state.removal != null) && identity != null) {
        RemoveDialog(
            identity = identity,
            removal = state.removal,
            onRemove = { typed, passphrase, force -> viewModel.remove(typed, passphrase, force) },
            onCancel = {
                confirmRemove = false
                viewModel.cancelRemoval()
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ProfileSection(identity: IdentityInfo, profile: ProfileState, onEdit: () -> Unit, onRetry: () -> Unit) {
    val view = (profile as? ProfileState.Ready)?.view
    SectionTitle(stringResource(R.string.acc_profile_title))
    SectionCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Identicon(identity.userId, size = 56.dp)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(
                    view?.name ?: identity.name ?: stringResource(R.string.acc_unnamed),
                    style = MaterialTheme.typography.titleLarge,
                )
                IdText(identity.userId, short = true, style = MaterialTheme.typography.bodyMedium)
            }
        }
        when (profile) {
            ProfileState.None -> Text(
                stringResource(R.string.acc_profile_no_node),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
            ProfileState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
            ProfileState.Locked -> StatusLine(Status.ATTENTION, stringResource(R.string.acc_locked), stringResource(R.string.acc_locked_detail), onRetry)
            is ProfileState.Failed -> StatusLine(Status.PROBLEM, stringResource(R.string.acc_profile_failed), profile.message, onRetry)
            is ProfileState.Ready -> {
                Field(stringResource(R.string.acc_profile_name), profile.view.name)
                Field(stringResource(R.string.acc_profile_bio), profile.view.bio)
                Field(stringResource(R.string.acc_profile_email), profile.view.email)
                OutlinedButton(onClick = onEdit, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp)) {
                    Text(stringResource(R.string.acc_profile_edit))
                }
            }
        }
    }
}

/** A labelled value of the profile; an empty one says so. */
@Composable
private fun Field(label: String, value: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value ?: stringResource(R.string.acc_profile_not_set),
            style = MaterialTheme.typography.bodyLarge,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun NodeSection(node: NodeAddress?, onShowQr: () -> Unit, onJoin: () -> Unit) {
    SectionTitle(stringResource(R.string.acc_node_title))
    SectionCard {
        if (node == null) {
            Text(
                stringResource(R.string.acc_node_none),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            Button(onClick = onJoin, modifier = Modifier.padding(start = 16.dp, bottom = 16.dp)) {
                Text(stringResource(R.string.acc_node_join))
            }
            return@SectionCard
        }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            node.nodeId?.let { Identicon(it, size = 48.dp) }
            Column(Modifier.padding(start = if (node.nodeId != null) 16.dp else 0.dp).weight(1f)) {
                Text(node.host(), style = MaterialTheme.typography.titleMedium)
                Text(node.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        node.nodeId?.let {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.acc_node_id), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IdText(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        OutlinedButton(onClick = onShowQr, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp)) {
            Text(stringResource(R.string.acc_node_show_qr))
        }
    }
}

// Read-only on purpose: plans are bought on the node's web portal, never through the app.
@Composable
private fun PlanSection(plan: String) {
    SectionTitle(stringResource(R.string.acc_plan_title))
    SectionCard {
        Column(Modifier.padding(16.dp)) {
            Text(plan, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.acc_plan_manage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Opening admin mode for the home node, in the violet that marks everything administrative. Shown to
 * every member: whether the identity administers the node is known only once the node is asked.
 */
@Composable
private fun AdminSection(node: NodeAddress, admin: AdminStart, onStart: () -> Unit) {
    SectionTitle(stringResource(R.string.acc_admin_title))
    SectionCard {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
            leadingContent = { Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
            headlineContent = { Text(stringResource(R.string.acc_admin_open), color = MaterialTheme.colorScheme.tertiary) },
            supportingContent = { Text(admin.error ?: stringResource(R.string.acc_admin_detail, node.host())) },
            trailingContent = if (admin.busy) {
                { CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.tertiary) }
            } else {
                null
            },
            modifier = Modifier.clickable(role = Role.Button, enabled = !admin.busy, onClick = onStart),
        )
    }
}

@Composable
private fun PhoneSection(error: String?, onRemove: () -> Unit) {
    SectionTitle(stringResource(R.string.acc_phone_title))
    SectionCard {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
            headlineContent = { Text(stringResource(R.string.acc_remove), color = MaterialTheme.colorScheme.error) },
            supportingContent = { Text(error ?: stringResource(R.string.acc_remove_detail)) },
            modifier = Modifier.clickable(role = Role.Button, onClick = onRemove),
        )
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val version = remember(context) { context.appVersion() }
    SectionTitle(stringResource(R.string.acc_about_title))
    SectionCard {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.acc_about_name), style = MaterialTheme.typography.titleMedium)
            version?.let {
                Text(
                    stringResource(R.string.acc_about_version, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.acc_about_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun Context.appVersion(): String? =
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()

@Composable
private fun NodeQrDialog(node: NodeAddress, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.acc_node_qr_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                rememberQrBitmap(nodeQrPayload(node))?.let {
                    Image(it, contentDescription = stringResource(R.string.acc_node_qr_desc), modifier = Modifier.size(220.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(node.host(), style = MaterialTheme.typography.titleSmall)
                node.nodeId?.let { IdText(it, short = true, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.acc_node_qr_body), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.acc_done)) } },
    )
}

@Composable
private fun EditProfileDialog(
    edit: ProfileEdit,
    onNameChange: (String) -> Unit,
    onBioChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!edit.busy) onCancel() },
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.acc_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = edit.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.acc_profile_name)) },
                    singleLine = true,
                    enabled = !edit.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = edit.bio,
                    onValueChange = onBioChange,
                    label = { Text(stringResource(R.string.acc_profile_bio)) },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !edit.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = edit.email,
                    onValueChange = onEmailChange,
                    label = { Text(stringResource(R.string.acc_profile_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                    enabled = !edit.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (edit.needsPassphrase) {
                    Text(stringResource(R.string.acc_edit_passphrase_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = edit.passphrase,
                        onValueChange = onPassphraseChange,
                        label = { Text(stringResource(R.string.acc_edit_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        singleLine = true,
                        enabled = !edit.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                edit.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (edit.busy) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                TextButton(onClick = onSave, enabled = !edit.needsPassphrase || edit.passphrase.isNotEmpty()) {
                    Text(stringResource(R.string.acc_edit_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !edit.busy) { Text(stringResource(R.string.acc_cancel)) }
        },
    )
}

/**
 * Removing the identity from this phone takes typing the first characters of its id, and says
 * plainly when no checked backup exists: then the identity is gone for good.
 */
@Composable
private fun RemoveDialog(
    identity: IdentityInfo,
    removal: Removal?,
    onRemove: (typed: String, passphrase: String?, force: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var typed by rememberSaveable(identity.userId) { mutableStateOf("") }
    var passphrase by rememberSaveable(identity.userId) { mutableStateOf("") }
    val code = removalCode(identity.userId)
    val busy = removal?.busy == true
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.acc_remove_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Identicon(identity.userId, size = 40.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(identity.name ?: stringResource(R.string.acc_unnamed), style = MaterialTheme.typography.titleSmall)
                        IdText(identity.userId, short = true, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (identity.backupVerified) {
                    Text(stringResource(R.string.acc_remove_verified))
                } else {
                    Text(stringResource(R.string.acc_remove_unverified), color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.acc_remove_type, code))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.acc_remove_type_label)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (removal?.needsPassphrase == true) {
                    Text(stringResource(R.string.acc_remove_passphrase))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(R.string.acc_edit_passphrase)) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                removal?.unreachable?.let {
                    Text(stringResource(R.string.acc_remove_unreachable, it), color = MaterialTheme.colorScheme.error)
                }
                removal?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val force = removal?.unreachable != null
            TextButton(
                onClick = { onRemove(typed, passphrase.ifEmpty { null }, force) },
                enabled = !busy && typed.trim() == code && (removal?.needsPassphrase != true || passphrase.isNotEmpty()),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(if (force) R.string.acc_remove_anyway else R.string.acc_remove_action))
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.acc_cancel)) } },
    )
}
