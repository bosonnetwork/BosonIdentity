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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.director.client.FederatedNode
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.Identicon
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.designsystem.component.copyToClipboard
import io.bosonnetwork.identity.core.designsystem.theme.brand
import io.bosonnetwork.service.Admission

/** The paged relationship list; query and page state belong to its own section view model. */
@Composable
internal fun FederationNodesPane(
    refreshes: Int,
    changes: Int,
    actions: FederationViewModel,
    viewModel: FederationNodesViewModel = hiltViewModel(),
) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val review by actions.review.collectAsStateWithLifecycle()
    val pages = (content as? Loaded.Ready)?.value
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    RefreshOn(refreshes, viewModel::refreshNodes)
    RefreshOn(changes, viewModel::refreshNodes)
    LaunchedEffect(pages) {
        if (pages != null && pages.nodes.none { it.id == selectedId }) selectedId = null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            FederationToolbar(FederationView.NODES, actions::selectView, review,
                actions::reviewPending, actions::refreshReviewCount,
                error = if (state.invalid) stringResource(R.string.adm_fed_invalid_id) else null) { modifier ->
                val searchDescription = stringResource(R.string.adm_fed_search_nodes)
                OutlinedTextField(
                    value = state.query, onValueChange = viewModel::onQueryChange,
                    placeholder = { Text(stringResource(R.string.adm_fed_node_id)) },
                    trailingIcon = if (state.query.isNotEmpty()) {
                        { IconButton(onClick = viewModel::clearSearch) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.adm_fed_clear_search)) } }
                    } else null,
                    singleLine = true, isError = state.invalid,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = modifier.semantics { contentDescription = searchDescription },
                )
            }
            if (pages?.enabled == false) {
                SectionCard(Modifier.padding(16.dp)) { Text(stringResource(R.string.adm_fed_off), modifier = Modifier.padding(16.dp)) }
            } else if (state.pending) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.tertiary)
            } else if (!state.invalid) {
                LoadedContent(content, onRetry = viewModel::refreshNodes) { loaded ->
                    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp)) {
                        item {
                            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.tertiary)
                            Text(stringResource(R.string.adm_fed_nodes_count, loaded.nodes.size, loaded.total),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        items(loaded.nodes, key = { it.id }) { node ->
                            NodeRow(node, actions, onClick = { selectedId = node.id })
                        }
                        if (loaded.nodes.isEmpty()) item {
                            Text(stringResource(if (state.appliedQuery.isEmpty()) R.string.adm_fed_no_nodes else R.string.adm_fed_no_matching_nodes),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                        }
                        if (loaded.more) item {
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
        if (pages?.enabled == true) FederationActions(
            nodes = true,
            onAdmit = { actions.startDecision(FederationDecision.ADMIT) },
            onSuspend = { actions.startDecision(FederationDecision.SUSPEND) },
            onPropose = actions::startPropose,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    pages?.nodes?.firstOrNull { it.id == selectedId }?.let { node ->
        NodeDetailSheet(node, onDismiss = { selectedId = null }, onDescriptor = {
            selectedId = null
            actions.openDescriptor(node.id)
        })
    }
}

@Composable
private fun NodeRow(node: FederatedNodeView, actions: FederationViewModel, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.adm_fed_node_details), onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Identicon(node.id, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IdText(node.id, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
                FederationHealthIndicator(node.health)
            }
            NodeBadges(node)
            if (node.updatedAt > 0) Text(stringResource(R.string.adm_fed_updated, relative(node.updatedAt)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
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
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NodeBadges(node: FederatedNodeView) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FederationBadge(relationshipState(node.state), Icons.Filled.Handshake,
            tint = if (node.state == FederatedNode.State.SUSPENDED) MaterialTheme.colorScheme.error else Color.Unspecified,
            description = stringResource(R.string.adm_fed_relationship, relationshipState(node.state)))
        FederationBadge(admissionReason(node.admission), Icons.Filled.Shield, tint = admissionColor(node.admission),
            description = stringResource(R.string.adm_fed_admission, admissionReason(node.admission)))
        if (node.reach != null && node.reach != node.admission)
            FederationBadge(stringResource(R.string.adm_fed_sending, admissionReason(node.reach)), Icons.AutoMirrored.Filled.ArrowForward,
                tint = admissionColor(node.reach))
    }
}

@Composable
private fun admissionColor(reason: Admission.Reason?): Color = when (reason) {
    Admission.Reason.OPEN, Admission.Reason.ACTIVE -> MaterialTheme.brand.success
    Admission.Reason.BLOCKED, Admission.Reason.SUSPENDED -> MaterialTheme.colorScheme.error
    null -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.brand.warning
}

/** This sheet reads the loaded relationship only. Remote descriptor retrieval is an explicit act. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NodeDetailSheet(node: FederatedNodeView, onDismiss: () -> Unit, onDescriptor: () -> Unit) {
    val context = LocalContext.current
    val idLabel = stringResource(R.string.adm_fed_node_id)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.brand.card,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Identicon(node.id, size = 40.dp)
                Text(stringResource(R.string.adm_fed_node_details), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.adm_done)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) { MonoText(node.id) }
                IconButton(onClick = { copyToClipboard(context, idLabel, node.id) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.adm_fed_copy_id))
                }
            }
            NodeBadges(node)
            Text(stringResource(R.string.adm_fed_admission, admissionReason(node.admission)))
            Text(stringResource(R.string.adm_fed_sending, admissionReason(node.reach)))
            node.origin?.let { Text(originLabel(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            node.note?.takeIf { it.isNotBlank() }?.let { FederationDetailField(stringResource(R.string.adm_fed_detail_note), it) }
            if (node.createdAt > 0) Text(stringResource(R.string.adm_fed_created, context.date(node.createdAt)))
            if (node.updatedAt > 0) Text(stringResource(R.string.adm_fed_updated, context.date(node.updatedAt)))
            FederationDetailField(stringResource(R.string.adm_fed_detail_health), healthDescription(node.health))
            node.health?.let { health ->
                if (health.lastSeenAt > 0) Text(stringResource(R.string.adm_fed_last_seen, relative(health.lastSeenAt)))
                if (health.lastSuccessAt > 0) Text(stringResource(R.string.adm_fed_last_success, relative(health.lastSuccessAt)))
                if (health.lastFailureAt > 0) Text(stringResource(R.string.adm_fed_last_failure, relative(health.lastFailureAt)))
                health.lastFailureDetail.orElse(null)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            OutlinedButton(onClick = onDescriptor) { Text(stringResource(R.string.adm_fed_descriptor)) }
        }
    }
}

@Composable
private fun originLabel(origin: String): String = stringResource(when (origin) {
    "admin" -> R.string.adm_fed_origin_admin
    "protocol" -> R.string.adm_fed_origin_protocol
    "auto" -> R.string.adm_fed_origin_auto
    else -> R.string.adm_fed_unknown
})

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
