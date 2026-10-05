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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.director.client.FederationProposal
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.Identicon
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.designsystem.component.shortId
import io.bosonnetwork.identity.core.designsystem.theme.brand

/** Federation: the federated nodes and the proposals, proposing and removing. */
@Composable
internal fun FederationTab(refreshes: Int, viewModel: FederationViewModel = hiltViewModel()) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    RefreshOn(refreshes, viewModel::refresh)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LoadedContent(content, onRetry = viewModel::refresh) { federation ->
                if (!federation.enabled) {
                    SectionCard { Text(stringResource(R.string.adm_fed_off), modifier = Modifier.padding(16.dp)) }
                    return@LoadedContent
                }
                Button(
                    onClick = viewModel::startPropose,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ),
                ) { Text(stringResource(R.string.adm_fed_propose)) }

                SectionTitle(stringResource(R.string.adm_fed_nodes))
                SectionCard {
                    if (federation.nodes.isEmpty()) Text(stringResource(R.string.adm_fed_no_nodes), modifier = Modifier.padding(16.dp))
                    federation.nodes.forEach { node ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
                            leadingContent = { Identicon(node.id, size = 40.dp) },
                            headlineContent = { Text(node.name ?: shortId(node.id)) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        stringResource(if (node.federated) R.string.adm_fed_federated else R.string.adm_fed_paused),
                                        node.endpoint,
                                        stringResource(R.string.adm_fed_reputation, node.reputation),
                                    ).joinToString(" - "),
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { viewModel.ask(FederationChange.RemoveNode(node)) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.adm_fed_remove_node))
                                }
                            },
                            modifier = Modifier.clickable(role = Role.Button) { viewModel.openServices(node) },
                        )
                    }
                }

                SectionTitle(stringResource(R.string.adm_fed_proposals))
                SectionCard {
                    if (federation.proposals.isEmpty()) Text(stringResource(R.string.adm_fed_no_proposals), modifier = Modifier.padding(16.dp))
                    federation.proposals.forEach { proposal ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
                            headlineContent = { Text(proposal.name ?: shortId(proposal.nodeId)) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        stringResource(
                                            if (proposal.role == FederationProposal.Role.OFFER) R.string.adm_fed_made else R.string.adm_fed_received,
                                        ),
                                        proposalStatus(proposal.status),
                                        proposal.proposedAt.takeIf { it > 0 }?.let { relative(it) },
                                    ).joinToString(" - "),
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { viewModel.ask(FederationChange.RemoveProposal(proposal)) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.adm_fed_remove_proposal))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    state.propose?.let { ProposeDialog(it, state, viewModel) }
    state.services?.let { (node, services) -> ServicesDialog(node, services, onClose = viewModel::closeServices) }
    state.proposed?.let { outcome ->
        AlertDialog(
            onDismissRequest = viewModel::proposedSeen,
            containerColor = MaterialTheme.brand.card,
            title = { Text(stringResource(R.string.adm_fed_proposed_title)) },
            text = { Text(outcome) },
            confirmButton = { TextButton(onClick = viewModel::proposedSeen) { Text(stringResource(R.string.adm_done)) } },
        )
    }
    when (val change = state.change) {
        is FederationChange.RemoveNode -> ConfirmByTypingDialog(
            title = stringResource(R.string.adm_fed_remove_node_title),
            body = stringResource(R.string.adm_fed_remove_node_body, change.node.name ?: shortId(change.node.id)),
            code = confirmCode(change.node.id),
            action = stringResource(R.string.adm_fed_remove_node),
            busy = state.busy,
            error = state.error,
            onConfirm = viewModel::confirm,
            onCancel = viewModel::cancel,
        )
        is FederationChange.RemoveProposal -> ConfirmDialog(
            title = stringResource(R.string.adm_fed_remove_proposal_title),
            body = stringResource(R.string.adm_fed_remove_proposal_body, change.proposal.name ?: shortId(change.proposal.nodeId)),
            action = stringResource(R.string.adm_fed_remove_proposal),
            busy = state.busy,
            error = state.error,
            onConfirm = { viewModel.confirm() },
            onCancel = viewModel::cancel,
            destructive = true,
        )
        null -> Unit
    }
}

@Composable
private fun ProposeDialog(proposal: Proposal, state: FederationUiState, viewModel: FederationViewModel) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) viewModel.cancel() },
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.adm_fed_propose_title)) },
        text = {
            ObscuredTouchGuard()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.adm_fed_propose_body))
                OutlinedTextField(
                    value = proposal.nodeId,
                    onValueChange = viewModel::onProposeIdChange,
                    label = { Text(stringResource(R.string.adm_fed_node_id)) },
                    singleLine = true,
                    enabled = !state.busy,
                    isError = proposal.nodeId.isNotBlank() && proposal.validId == null,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(state.error)
            }
        },
        confirmButton = {
            if (state.busy) {
                Busy()
            } else {
                TextButton(
                    onClick = viewModel::propose,
                    enabled = proposal.validId != null,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.tertiary),
                ) { Text(stringResource(R.string.adm_fed_propose_action)) }
            }
        },
        dismissButton = { TextButton(onClick = viewModel::cancel, enabled = !state.busy) { Text(stringResource(R.string.adm_cancel)) } },
    )
}

@Composable
private fun ServicesDialog(node: FederatedNodeView, services: Loaded<List<ServiceView>>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.brand.card,
        title = { Text(node.name ?: shortId(node.id)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IdText(node.id, style = MaterialTheme.typography.bodySmall)
                listOfNotNull(node.software, node.version).joinToString(" ").takeIf { it.isNotEmpty() }?.let { Text(it) }
                Text(stringResource(R.string.adm_fed_services), style = MaterialTheme.typography.titleSmall)
                LoadedContent(services, onRetry = {}) { list ->
                    if (list.isEmpty()) Text(stringResource(R.string.adm_fed_no_services))
                    list.forEach { service ->
                        Column {
                            Text(service.name, style = MaterialTheme.typography.bodyLarge)
                            service.endpoint?.let { MonoText(it) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.adm_done)) } },
    )
}

@Composable
private fun proposalStatus(status: FederationProposal.Status): String = stringResource(
    when (status) {
        FederationProposal.Status.CREATED -> R.string.adm_fed_status_created
        FederationProposal.Status.PROPOSED -> R.string.adm_fed_status_proposed
        FederationProposal.Status.ACCEPTED -> R.string.adm_fed_status_accepted
        FederationProposal.Status.DECLINED -> R.string.adm_fed_status_declined
        FederationProposal.Status.FAILED -> R.string.adm_fed_status_failed
    },
)
