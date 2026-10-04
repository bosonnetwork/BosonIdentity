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

package io.bosonnetwork.identity.feature.identity

import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import io.bosonnetwork.identity.core.qr.rememberQrBitmap

/**
 * The home screen: the identity, and a checklist of how well it is protected. [navigationIcon] goes
 * at the start of the top bar; the app puts its identity switcher there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityScreen(
    onBackup: (userId: String) -> Unit,
    onJoinNode: (userId: String) -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    viewModel: IdentityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showId by rememberSaveable { mutableStateOf(false) }
    val identity = state.identity

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.id_title)) },
                navigationIcon = navigationIcon,
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.id_refresh))
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
                IdentityCard(identity, state.node, onShowId = { showId = true })
                Checklist(identity, state.node, onBackup = { onBackup(identity.userId) }, onJoinNode = { onJoinNode(identity.userId) }, onRetry = viewModel::refresh)
            }
        }
    }

    if (showId && identity != null) {
        ShowIdDialog(identity.userId, onDismiss = { showId = false })
    }
}

@Composable
private fun IdentityCard(identity: IdentityInfo, node: NodeState, onShowId: () -> Unit) {
    val view = (node as? NodeState.Ready)?.view
    SectionCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Identicon(identity.userId, size = 56.dp)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(
                    view?.name ?: identity.name ?: stringResource(R.string.id_unnamed),
                    style = MaterialTheme.typography.titleLarge,
                )
                IdText(identity.userId, short = true, style = MaterialTheme.typography.bodyMedium)
                identity.homeNode?.let {
                    Text(
                        Uri.parse(it.url).host ?: it.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onShowId) { Text(stringResource(R.string.id_show_id)) }
            if (view?.admin == true) {
                // Admin is marked in the brand's violet everywhere it appears.
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.id_admin_badge)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                    border = null,
                )
            }
        }
    }
}

@Composable
private fun Checklist(
    identity: IdentityInfo,
    node: NodeState,
    onBackup: () -> Unit,
    onJoinNode: () -> Unit,
    onRetry: () -> Unit,
) {
    Text(stringResource(R.string.id_protection_title), style = MaterialTheme.typography.titleMedium)
    SectionCard {
        if (identity.backupVerified) {
            StatusLine(Status.OK, stringResource(R.string.id_backup_ok), stringResource(R.string.id_backup_ok_detail))
        } else {
            StatusLine(Status.ATTENTION, stringResource(R.string.id_backup_needed), stringResource(R.string.id_backup_needed_detail), onBackup)
        }
        StatusLine(Status.OK, stringResource(R.string.id_lock_ok), stringResource(R.string.id_lock_ok_detail))

        when (node) {
            NodeState.None -> StatusLine(Status.ATTENTION, stringResource(R.string.id_node_none), stringResource(R.string.id_node_none_detail), onJoinNode)
            NodeState.Loading -> {
                StatusLine(Status.OK, stringResource(R.string.id_node_checking), null)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            NodeState.Locked -> StatusLine(Status.ATTENTION, stringResource(R.string.id_locked), stringResource(R.string.id_locked_detail), onRetry)
            is NodeState.Failed -> StatusLine(Status.PROBLEM, stringResource(R.string.id_node_failed), node.message, onRetry)
            is NodeState.Ready -> {
                val view = node.view
                StatusLine(
                    Status.OK,
                    stringResource(R.string.id_node_ok),
                    view.plan?.let { stringResource(R.string.id_node_ok_plan, it) },
                )
                if (view.passphraseProtected) {
                    StatusLine(Status.OK, stringResource(R.string.id_passphrase_ok), stringResource(R.string.id_passphrase_ok_detail))
                    val codes = view.recoveryCodes
                    when {
                        view.recoveryMethods == null -> Unit
                        view.recoveryMethods > 0 -> StatusLine(
                            Status.OK,
                            stringResource(R.string.id_recovery_ok),
                            codes?.let { stringResource(R.string.id_recovery_codes_left, it) },
                        )
                        else -> StatusLine(Status.PROBLEM, stringResource(R.string.id_recovery_none), stringResource(R.string.id_recovery_none_detail))
                    }
                } else {
                    StatusLine(Status.ATTENTION, stringResource(R.string.id_passphrase_none), stringResource(R.string.id_passphrase_none_detail))
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.id_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ShowIdDialog(userId: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.id_show_id_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                rememberQrBitmap(userId)?.let {
                    Image(it, contentDescription = stringResource(R.string.id_show_id_qr), modifier = Modifier.size(220.dp))
                }
                Spacer(Modifier.height(12.dp))
                IdText(userId, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.id_show_id_body), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.id_done)) } },
    )
}
