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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.director.client.FederatedNode
import io.bosonnetwork.director.client.FederationProposal
import io.bosonnetwork.service.Admission
import io.bosonnetwork.service.FederatedNodeDescriptor
import io.bosonnetwork.service.FederationMode
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.Identicon
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.designsystem.component.shortId
import io.bosonnetwork.identity.core.designsystem.theme.brand

/** Federation: relationships and proposals, with explicit descriptor lookup. */
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
                                IconButton(onClick = { viewModel.ask(FederationChange.RemoveNode(node)) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.adm_fed_remove_node))
                                }
                            },
                            modifier = Modifier.clickable(role = Role.Button) { viewModel.openDescriptor(node.id) },
                        )
                    }
                }

                SectionTitle(stringResource(R.string.adm_fed_proposals))
                SectionCard {
                    if (federation.proposals.isEmpty()) Text(stringResource(R.string.adm_fed_no_proposals), modifier = Modifier.padding(16.dp))
                    federation.proposals.forEach { proposal ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
                            headlineContent = { IdText(proposal.nodeId) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(proposalDirection(proposal.role) + " - " + proposalStatus(proposal.status))
                                    proposal.note?.let { Text(it) }
                                    if (proposal.createdAt > 0) Text(stringResource(R.string.adm_fed_created, relative(proposal.createdAt)))
                                    if (proposal.answeredAt > 0) Text(stringResource(R.string.adm_fed_answered, relative(proposal.answeredAt)))
                                }
                            },
                            trailingContent = if (proposal.canRemove) {
                                {
                                    IconButton(onClick = { viewModel.ask(FederationChange.RemoveProposal(proposal)) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = stringResource(
                                            if (proposal.status == FederationProposal.Status.PENDING)
                                                R.string.adm_fed_withdraw_proposal else R.string.adm_fed_remove_proposal,
                                        ))
                                    }
                                }
                            } else null,
                        )
                    }
                }
            }
        }
    }

    state.propose?.let { ProposeDialog(it, state, viewModel) }
    state.descriptor?.let { (nodeId, descriptor) ->
        DescriptorDialog(nodeId, descriptor, onRetry = { viewModel.openDescriptor(nodeId) }, onClose = viewModel::closeDescriptor)
    }
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
            body = stringResource(R.string.adm_fed_remove_node_body, shortId(change.node.id)),
            code = confirmCode(change.node.id),
            action = stringResource(R.string.adm_fed_remove_node),
            busy = state.busy,
            error = state.error,
            onConfirm = viewModel::confirm,
            onCancel = viewModel::cancel,
        )
        is FederationChange.RemoveProposal -> ConfirmDialog(
            title = stringResource(if (change.proposal.status == FederationProposal.Status.PENDING)
                R.string.adm_fed_withdraw_proposal_title else R.string.adm_fed_remove_proposal_title),
            body = stringResource(if (change.proposal.status == FederationProposal.Status.PENDING)
                R.string.adm_fed_withdraw_proposal_body else R.string.adm_fed_remove_proposal_body, shortId(change.proposal.nodeId)),
            action = stringResource(if (change.proposal.status == FederationProposal.Status.PENDING)
                R.string.adm_fed_withdraw_proposal else R.string.adm_fed_remove_proposal),
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
private fun DescriptorDialog(
    nodeId: String,
    descriptor: Loaded<FederatedNodeDescriptor?>,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.adm_fed_descriptor)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer { MonoText(nodeId) }
                LoadedContent(descriptor, onRetry) { value ->
                    if (value == null) {
                        Text(stringResource(R.string.adm_fed_descriptor_unavailable))
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.adm_refresh)) }
                    } else {
                        value.name.orElse(null)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                        listOfNotNull(value.software.orElse(null), value.version.orElse(null))
                            .joinToString(" ").takeIf { it.isNotEmpty() }?.let { Text(it) }
                        value.website.orElse(null)?.let { Text(stringResource(R.string.adm_fed_website, it)) }
                        value.contact.orElse(null)?.let { Text(stringResource(R.string.adm_fed_contact, it)) }
                        Text(stringResource(R.string.adm_fed_mode, federationMode(value.mode.orElse(null))))
                        if (value.protocols.isNotEmpty()) Text(stringResource(R.string.adm_fed_protocols, value.protocols.joinToString(", ")))
                        if (value.issuedAt.toEpochMilli() > 0) Text(stringResource(R.string.adm_fed_signed, context.date(value.issuedAt.toEpochMilli())))
                        if (value.expiresAt.toEpochMilli() > 0) Text(stringResource(R.string.adm_fed_expires, context.date(value.expiresAt.toEpochMilli())))
                        Text(stringResource(R.string.adm_fed_services), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.adm_fed_service_hint), style = MaterialTheme.typography.bodySmall)
                        if (value.services.isEmpty()) Text(stringResource(R.string.adm_fed_no_services))
                        value.services.forEach { service ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(service.name.orElse(service.type), style = MaterialTheme.typography.bodyLarge)
                                if (service.name.isPresent) MonoText(service.type)
                                SelectionContainer { MonoText(service.peerId.toString()) }
                                MonoText(service.endpoint)
                                Text(stringResource(if (service.isFederation) R.string.adm_fed_service_federates else R.string.adm_fed_service_local))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.adm_done)) } },
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

@Composable
private fun federationMode(mode: FederationMode?): String = stringResource(
    when (mode) {
        FederationMode.OPEN -> R.string.adm_fed_mode_open
        FederationMode.MUTUAL -> R.string.adm_fed_mode_mutual
        FederationMode.ALLOWLIST -> R.string.adm_fed_mode_allowlist
        null -> R.string.adm_fed_mode_unknown
    },
)

@Composable
private fun proposalDirection(role: FederationProposal.Role?): String = stringResource(
    when (role) {
        FederationProposal.Role.OFFER -> R.string.adm_fed_made
        FederationProposal.Role.ANSWER -> R.string.adm_fed_received
        null -> R.string.adm_fed_unknown
    },
)

@Composable
private fun proposalStatus(status: FederationProposal.Status?): String = stringResource(
    when (status) {
        FederationProposal.Status.PENDING -> R.string.adm_fed_status_pending
        FederationProposal.Status.ACCEPTED -> R.string.adm_fed_status_accepted
        FederationProposal.Status.DECLINED -> R.string.adm_fed_status_declined
        FederationProposal.Status.WITHDRAWN -> R.string.adm_fed_status_withdrawn
        FederationProposal.Status.EXPIRED -> R.string.adm_fed_status_expired
        null -> R.string.adm_fed_unknown
    },
)
