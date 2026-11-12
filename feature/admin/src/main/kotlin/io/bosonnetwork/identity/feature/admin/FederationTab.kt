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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.director.client.FederationProposal
import io.bosonnetwork.service.FederatedNodeDescriptor
import io.bosonnetwork.service.FederationMode
import io.bosonnetwork.identity.core.designsystem.component.shortId
import io.bosonnetwork.identity.core.designsystem.theme.brand

/** Federation views share admin mode and actions, with independent content and scroll state. */
@Composable
internal fun FederationTab(refreshes: Int, viewModel: FederationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved = rememberSaveableStateHolder()
    RefreshOn(refreshes, viewModel::refreshReviewCount)
    saved.SaveableStateProvider(state.view.name) {
        when (state.view) {
            FederationView.NODES -> FederationNodesPane(refreshes, state.nodeChanges, viewModel)
            FederationView.PROPOSALS -> FederationProposalsPane(refreshes, viewModel)
            FederationView.ACTIVITY -> FederationActivityPane(refreshes, state.nodeChanges, viewModel)
            FederationView.HOSTS -> FederationHostsPane(refreshes, state.nodeChanges, viewModel)
        }
    }

    state.decision?.let { NodeDecisionDialog(it, state, viewModel) }
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
    state.notice?.let { notice ->
        AlertDialog(onDismissRequest = viewModel::noticeSeen, containerColor = MaterialTheme.brand.card,
            title = { Text(stringResource(R.string.adm_fed_proposal_changed_title)) },
            text = { Text(notice) },
            confirmButton = { TextButton(onClick = viewModel::noticeSeen) { Text(stringResource(R.string.adm_done)) } })
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
        is FederationChange.AnswerProposal -> ConfirmDialog(
            title = stringResource(if (change.accept) R.string.adm_fed_accept_title else R.string.adm_fed_decline_title),
            body = stringResource(if (change.accept) R.string.adm_fed_accept_body else R.string.adm_fed_decline_body, shortId(change.proposal.nodeId)),
            action = stringResource(if (change.accept) R.string.adm_fed_accept else R.string.adm_fed_decline),
            busy = state.busy, error = state.error,
            onConfirm = { viewModel.confirm() }, onCancel = viewModel::cancel, destructive = !change.accept,
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
                OutlinedTextField(
                    value = proposal.note, onValueChange = viewModel::onProposeNoteChange,
                    label = { Text(stringResource(R.string.adm_fed_note)) },
                    supportingText = { Text(stringResource(R.string.adm_fed_note_length, proposal.note.length)) },
                    isError = proposal.note.length > 256, enabled = !state.busy,
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
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
                    enabled = proposal.valid,
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
private fun federationMode(mode: FederationMode?): String = stringResource(
    when (mode) {
        FederationMode.OPEN -> R.string.adm_fed_mode_open
        FederationMode.MUTUAL -> R.string.adm_fed_mode_mutual
        FederationMode.ALLOWLIST -> R.string.adm_fed_mode_allowlist
        null -> R.string.adm_fed_mode_unknown
    },
)
