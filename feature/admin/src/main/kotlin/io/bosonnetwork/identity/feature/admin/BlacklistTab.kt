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

package io.bosonnetwork.identity.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.designsystem.component.shortId
import io.bosonnetwork.identity.core.designsystem.theme.brand

/** Blacklist: the nodes and hosts refused, adding and removing one. */
@Composable
internal fun BlacklistTab(refreshes: Int, viewModel: BlacklistViewModel = hiltViewModel()) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    RefreshOn(refreshes, viewModel::refresh)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = viewModel::startAdd,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary),
            ) { Text(stringResource(R.string.adm_bl_add)) }
            LoadedContent(content, onRetry = viewModel::refresh) { entries ->
                SectionCard {
                    if (entries.isEmpty()) Text(stringResource(R.string.adm_bl_none), modifier = Modifier.padding(16.dp))
                    entries.forEach { entry ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
                            overlineContent = {
                                Text(stringResource(if (entry.nodeId != null) R.string.adm_bl_kind_node else R.string.adm_bl_kind_host))
                            },
                            headlineContent = { Text(entry.nodeId?.let { shortId(it) } ?: entry.host.orEmpty()) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        entry.reason,
                                        if (entry.auto) stringResource(R.string.adm_bl_auto) else null,
                                        context.date(entry.createdAt),
                                    ).joinToString(" - "),
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { viewModel.askRemove(entry) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.adm_bl_remove))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    state.add?.let { AddDialog(it, state, viewModel) }
    state.remove?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.adm_bl_remove_title),
            body = stringResource(R.string.adm_bl_remove_body, entry.nodeId?.let { shortId(it) } ?: entry.host.orEmpty()),
            action = stringResource(R.string.adm_bl_remove),
            busy = state.busy,
            error = state.error,
            onConfirm = viewModel::remove,
            onCancel = viewModel::cancel,
        )
    }
}

/**
 * Adding a node or a host. Blacklisting is destructive (the node refuses it from then on), so once
 * the form is filled, the target's first characters are typed again to confirm.
 */
@Composable
private fun AddDialog(form: BlacklistForm, state: BlacklistUiState, viewModel: BlacklistViewModel) {
    var typed by rememberSaveable(form.target, form.kind) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!state.busy) viewModel.cancel() },
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.adm_bl_add_title)) },
        text = {
            ObscuredTouchGuard()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BlacklistKind.entries.forEach { kind ->
                        FilterChip(
                            selected = form.kind == kind,
                            onClick = { viewModel.onKindChange(kind) },
                            enabled = !state.busy,
                            label = { Text(stringResource(if (kind == BlacklistKind.NODE) R.string.adm_bl_kind_node else R.string.adm_bl_kind_host)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                        )
                    }
                }
                OutlinedTextField(
                    value = form.target,
                    onValueChange = viewModel::onTargetChange,
                    label = { Text(stringResource(if (form.kind == BlacklistKind.NODE) R.string.adm_bl_node_id else R.string.adm_bl_host)) },
                    singleLine = true,
                    enabled = !state.busy,
                    isError = form.target.isNotBlank() && !form.valid,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.reason,
                    onValueChange = viewModel::onReasonChange,
                    label = { Text(stringResource(R.string.adm_bl_reason)) },
                    singleLine = true,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (form.confirming) {
                    Text(stringResource(R.string.adm_bl_add_warning))
                    TypeToConfirm(form.code, typed, onTypedChange = { typed = it }, enabled = !state.busy)
                }
                ErrorText(state.error)
            }
        },
        confirmButton = {
            if (state.busy) {
                Busy()
            } else {
                TextButton(
                    onClick = { viewModel.add(typed) },
                    enabled = form.valid && (!form.confirming || confirms(typed, form.code)),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.adm_bl_add_action)) }
            }
        },
        dismissButton = { TextButton(onClick = viewModel::cancel, enabled = !state.busy) { Text(stringResource(R.string.adm_cancel)) } },
    )
}
