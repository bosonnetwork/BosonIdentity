/*
 * Copyright (c) 2026 -      bosonnetwork.io
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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.director.client.FederatedNode
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.Identicon
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.designsystem.theme.brand
import io.bosonnetwork.service.Admission

/** The paged relationship list; query and page state belong to its own section view model. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FederationNodesPane(
    refreshes: Int,
    changes: Int,
    actions: FederationViewModel,
    viewModel: FederationNodesViewModel = hiltViewModel(),
) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    RefreshOn(refreshes, viewModel::refreshNodes)
    RefreshOn(changes, viewModel::refreshNodes)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if ((content as? Loaded.Ready)?.value?.enabled == false) {
            SectionCard(Modifier.padding(16.dp)) { Text(stringResource(R.string.adm_fed_off), modifier = Modifier.padding(16.dp)) }
            return@Column
        }
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.query, onValueChange = viewModel::onQueryChange,
                label = { Text(stringResource(R.string.adm_fed_search_nodes)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (state.query.isNotEmpty()) {
                    { IconButton(onClick = viewModel::clearSearch) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.adm_fed_clear_search)) } }
                } else null,
                singleLine = true, isError = state.invalid,
                supportingText = if (state.invalid) { { Text(stringResource(R.string.adm_fed_invalid_id)) } } else null,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth(),
            )
            if (content is Loaded.Ready) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { actions.startDecision(FederationDecision.ADMIT) }, colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary,
                    )) { Text(stringResource(R.string.adm_fed_admit_node)) }
                    OutlinedButton(onClick = { actions.startDecision(FederationDecision.SUSPEND) }) { Text(stringResource(R.string.adm_fed_suspend_node)) }
                    OutlinedButton(onClick = actions::startPropose) { Text(stringResource(R.string.adm_fed_propose)) }
                }
            }
        }
        if (state.invalid) return@Column
        if (state.pending) {
            LinearProgressIndicator(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.tertiary)
            return@Column
        }
        LoadedContent(content, onRetry = viewModel::refreshNodes) { pages ->
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                item {
                    if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(R.string.adm_fed_nodes_count, pages.nodes.size, pages.total),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                items(pages.nodes, key = { it.id }) { node -> NodeRow(node, actions) }
                if (pages.nodes.isEmpty()) item {
                    Text(stringResource(if (state.appliedQuery.isEmpty()) R.string.adm_fed_no_nodes else R.string.adm_fed_no_matching_nodes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
                if (pages.more) item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.loadingMore) Busy() else OutlinedButton(onClick = viewModel::loadMore, enabled = !state.refreshing) {
                            Text(stringResource(R.string.adm_fed_load_more))
                        }
                        ErrorText(state.moreError)
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeRow(node: FederatedNodeView, actions: FederationViewModel) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        leadingContent = { Identicon(node.id, size = 40.dp) },
        headlineContent = { IdText(node.id) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.adm_fed_relationship, relationshipState(node.state)))
                node.origin?.let { origin ->
                    Text(stringResource(when (origin) {
                        "admin" -> R.string.adm_fed_origin_admin
                        "protocol" -> R.string.adm_fed_origin_protocol
                        "auto" -> R.string.adm_fed_origin_auto
                        else -> R.string.adm_fed_unknown
                    }))
                }
                Text(stringResource(R.string.adm_fed_admission, admissionReason(node.admission)))
                if (node.reach != null && node.reach != node.admission)
                    Text(stringResource(R.string.adm_fed_sending, admissionReason(node.reach)))
                val health = node.health
                Text(when {
                    health == null || health.lastSeenAt == 0L -> stringResource(R.string.adm_fed_unobserved)
                    health.isFailing -> stringResource(R.string.adm_fed_failures, health.consecutiveFailures)
                    health.lastSuccessAt > 0 -> stringResource(R.string.adm_fed_recent_success)
                    else -> stringResource(R.string.adm_fed_observed)
                })
                node.note?.let { Text(it) }
                if (node.updatedAt > 0) Text(stringResource(R.string.adm_fed_updated, relative(node.updatedAt)))
            }
        },
        trailingContent = {
            Column {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.adm_fed_node_actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MaterialTheme.brand.card) {
                    if (node.state != FederatedNode.State.ACTIVE) DropdownMenuItem(
                        text = { Text(stringResource(if (node.state == FederatedNode.State.PENDING_IN) R.string.adm_fed_accept_admit else R.string.adm_fed_admit_node)) },
                        onClick = { menu = false; actions.startDecision(FederationDecision.ADMIT, node) },
                    )
                    if (node.state != FederatedNode.State.SUSPENDED) DropdownMenuItem(
                        text = { Text(stringResource(R.string.adm_fed_suspend_node)) },
                        onClick = { menu = false; actions.startDecision(FederationDecision.SUSPEND, node) },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.adm_fed_descriptor)) },
                        onClick = { menu = false; actions.openDescriptor(node.id) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.adm_fed_remove_node), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; actions.ask(FederationChange.RemoveNode(node)) })
                }
            }
        },
        modifier = Modifier.clickable(role = Role.Button) { actions.openDescriptor(node.id) },
    )
}

/** Operator intent is confirmed here; a suspension also requires the standard typed ID code. */
@Composable
internal fun NodeDecisionDialog(form: NodeDecision, state: FederationUiState, viewModel: FederationViewModel) {
    var typed by rememberSaveable(form.nodeId) { mutableStateOf("") }
    val suspending = form.decision == FederationDecision.SUSPEND
    AlertDialog(
        onDismissRequest = { if (!state.busy) viewModel.cancel() }, containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(if (suspending) R.string.adm_fed_suspend_node else R.string.adm_fed_admit_node)) },
        text = {
            ObscuredTouchGuard()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (suspending) R.string.adm_fed_suspend_body else R.string.adm_fed_admit_body))
                OutlinedTextField(
                    value = form.nodeId, onValueChange = viewModel::onDecisionIdChange,
                    label = { Text(stringResource(R.string.adm_fed_node_id)) },
                    singleLine = true, readOnly = form.existing, enabled = !state.busy,
                    isError = form.nodeId.isNotBlank() && form.validId == null,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.note, onValueChange = viewModel::onDecisionNoteChange,
                    label = { Text(stringResource(R.string.adm_fed_note)) },
                    supportingText = { Text(stringResource(R.string.adm_fed_note_length, form.note.length)) },
                    isError = form.note.length > 256, enabled = !state.busy,
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                )
                if (suspending && form.confirming) TypeToConfirm(form.code, typed, onTypedChange = { typed = it }, enabled = !state.busy)
                ErrorText(state.error)
            }
        },
        confirmButton = {
            if (state.busy) Busy() else TextButton(
                onClick = { viewModel.saveDecision(typed) },
                enabled = form.valid && (!form.confirming || confirms(typed, form.code)),
                colors = ButtonDefaults.textButtonColors(contentColor = if (suspending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary),
            ) { Text(stringResource(if (suspending) R.string.adm_fed_suspend else R.string.adm_fed_admit)) }
        },
        dismissButton = { TextButton(onClick = viewModel::cancel, enabled = !state.busy) { Text(stringResource(R.string.adm_cancel)) } },
    )
}

@Composable
private fun relationshipState(state: FederatedNode.State?): String = stringResource(
    when (state) {
        FederatedNode.State.ACTIVE -> R.string.adm_fed_active
        FederatedNode.State.SUSPENDED -> R.string.adm_fed_suspended
        FederatedNode.State.PENDING_OUT -> R.string.adm_fed_pending_out
        FederatedNode.State.PENDING_IN -> R.string.adm_fed_pending_in
        FederatedNode.State.DECLINED -> R.string.adm_fed_status_declined
        null -> R.string.adm_fed_unknown
    },
)

@Composable
private fun admissionReason(reason: Admission.Reason?): String = stringResource(
    when (reason) {
        Admission.Reason.OPEN -> R.string.adm_fed_reason_open
        Admission.Reason.ACTIVE -> R.string.adm_fed_active
        Admission.Reason.PENDING -> R.string.adm_fed_status_pending
        Admission.Reason.NO_RELATIONSHIP -> R.string.adm_fed_reason_no_relationship
        Admission.Reason.DECLINED -> R.string.adm_fed_status_declined
        Admission.Reason.NOT_FEDERATING -> R.string.adm_fed_reason_not_federating
        Admission.Reason.SUSPENDED -> R.string.adm_fed_suspended
        Admission.Reason.BLOCKED -> R.string.adm_fed_reason_blocked
        Admission.Reason.DISABLED -> R.string.adm_fed_reason_disabled
        Admission.Reason.UNKNOWN_PEER -> R.string.adm_fed_reason_unknown_peer
        Admission.Reason.UNVERIFIED_PEER -> R.string.adm_fed_reason_unverified_peer
        null -> R.string.adm_fed_unknown
    },
)

