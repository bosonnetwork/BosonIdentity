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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.director.client.FederationProposal
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.Identicon
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.designsystem.component.copyToClipboard
import io.bosonnetwork.identity.core.designsystem.theme.brand

/** Proposal history retains its own query, filters and scroll position within admin mode. */
@Composable
internal fun FederationProposalsPane(refreshes: Int, viewModel: FederationViewModel) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val state by viewModel.proposals.collectAsStateWithLifecycle()
    val review by viewModel.review.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val federation = (content as? Loaded.Ready)?.value
    RefreshOn(refreshes, viewModel::refreshProposals)
    LaunchedEffect(state.appliedQuery, state.role, state.status) { list.scrollToItem(0) }
    LaunchedEffect(federation) {
        if (federation != null && federation.proposals.none { it.id == selectedId }) selectedId = null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            FederationToolbar(FederationView.PROPOSALS, viewModel::selectView, review,
                viewModel::reviewPending, viewModel::refreshReviewCount,
                error = if (state.invalid) stringResource(R.string.adm_fed_invalid_id) else null) { modifier ->
                val searchDescription = stringResource(R.string.adm_fed_search_nodes)
                OutlinedTextField(
                    value = state.query, onValueChange = viewModel::onProposalQueryChange,
                    placeholder = { Text(stringResource(R.string.adm_fed_node_id)) },
                    trailingIcon = if (state.query.isNotEmpty()) {
                        { IconButton(onClick = viewModel::clearProposalSearch) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.adm_fed_clear_search)) } }
                    } else null,
                    singleLine = true, isError = state.invalid,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = modifier.semantics { contentDescription = searchDescription },
                )
            }
            if (federation?.enabled == false) {
                SectionCard(Modifier.padding(16.dp)) { Text(stringResource(R.string.adm_fed_off), modifier = Modifier.padding(16.dp)) }
            } else {
                ProposalFilters(state, viewModel::setProposalFilters)
                if (state.pending) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.tertiary)
                } else if (!state.invalid) LoadedContent(content, onRetry = viewModel::refreshProposals) { loaded ->
                    LazyColumn(Modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp)) {
                        item {
                            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.tertiary)
                            Text(stringResource(R.string.adm_fed_proposals_count, loaded.proposals.size, loaded.total),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        if (loaded.proposals.isEmpty()) item {
                            val filtered = state.appliedQuery.isNotEmpty() || state.role != null || state.status != null
                            Text(stringResource(if (filtered) R.string.adm_fed_no_matching_proposals else R.string.adm_fed_no_proposals),
                                modifier = Modifier.padding(16.dp))
                        }
                        items(loaded.proposals, key = { it.id }) { proposal ->
                            ProposalRow(proposal, onClick = { selectedId = proposal.id }, onChange = viewModel::ask)
                        }
                        if (loaded.more) item {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (state.loadingMore) Busy() else OutlinedButton(onClick = viewModel::loadMoreProposals, enabled = !state.refreshing) {
                                    Text(stringResource(R.string.adm_fed_load_more))
                                }
                                ErrorText(state.moreError)
                            }
                        }
                    }
                }
            }
        }
        if (federation?.enabled == true) FederationActions(
            nodes = false,
            onAdmit = {}, onSuspend = {}, onPropose = viewModel::startPropose,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    federation?.proposals?.firstOrNull { it.id == selectedId }?.let { proposal ->
        ProposalDetailSheet(proposal, onDismiss = { selectedId = null }, onChange = {
            selectedId = null
            viewModel.ask(it)
        })
    }
}

/** Two compact menus keep the full direction/status matrix available on narrow screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProposalFilters(state: ProposalListState, onChange: (FederationProposal.Role?, FederationProposal.Status?) -> Unit) {
    var direction by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(false) }
    val all = stringResource(R.string.adm_fed_filter_all)
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            FilterChip(selected = state.role != null, onClick = { direction = true },
                label = { Text(stringResource(R.string.adm_fed_direction, if (state.role == null) all else proposalDirection(state.role))) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) })
            DropdownMenu(expanded = direction, onDismissRequest = { direction = false }, containerColor = MaterialTheme.brand.card) {
                (listOf(null) + FederationProposal.Role.entries).forEach { role ->
                    DropdownMenuItem(text = { Text(if (role == null) all else proposalDirection(role)) },
                        onClick = { direction = false; onChange(role, state.status) })
                }
            }
        }
        Box {
            FilterChip(selected = state.status != null, onClick = { status = true },
                label = { Text(stringResource(R.string.adm_fed_status_filter, if (state.status == null) all else proposalStatus(state.status))) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) })
            DropdownMenu(expanded = status, onDismissRequest = { status = false }, containerColor = MaterialTheme.brand.card) {
                (listOf(null) + FederationProposal.Status.entries).forEach { value ->
                    DropdownMenuItem(text = { Text(if (value == null) all else proposalStatus(value)) },
                        onClick = { status = false; onChange(state.role, value) })
                }
            }
        }
    }
}

@Composable
private fun ProposalRow(proposal: ProposalView, onClick: () -> Unit, onChange: (FederationChange) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.adm_fed_proposal_details), onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Identicon(proposal.nodeId, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            IdText(proposal.nodeId, style = MaterialTheme.typography.bodyLarge)
            ProposalBadges(proposal)
            val date = if (proposal.answeredAt > 0) R.string.adm_fed_answered else R.string.adm_fed_created
            val at = if (proposal.answeredAt > 0) proposal.answeredAt else proposal.createdAt
            if (at > 0) Text(stringResource(date, relative(at)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (proposal.canRemove || proposal.canAnswer) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.adm_fed_proposal_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MaterialTheme.brand.card) {
                    if (proposal.canAnswer) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.adm_fed_accept)) },
                            onClick = { menu = false; onChange(FederationChange.AnswerProposal(proposal, true)) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.adm_fed_decline)) },
                            onClick = { menu = false; onChange(FederationChange.AnswerProposal(proposal, false)) })
                    }
                    if (proposal.canRemove) DropdownMenuItem(
                        text = { Text(stringResource(if (proposal.status == FederationProposal.Status.PENDING)
                            R.string.adm_fed_withdraw_proposal else R.string.adm_fed_remove_proposal)) },
                        onClick = { menu = false; onChange(FederationChange.RemoveProposal(proposal)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProposalBadges(proposal: ProposalView) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FederationBadge(proposalDirection(proposal.role), when (proposal.role) {
            FederationProposal.Role.ANSWER -> Icons.AutoMirrored.Filled.CallReceived
            FederationProposal.Role.OFFER -> Icons.AutoMirrored.Filled.CallMade
            null -> Icons.AutoMirrored.Filled.HelpOutline
        })
        val status = proposal.status
        val color = when (status) {
            FederationProposal.Status.ACCEPTED -> MaterialTheme.brand.success
            FederationProposal.Status.PENDING -> MaterialTheme.brand.warning
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        FederationBadge(proposalStatus(status), when (status) {
            FederationProposal.Status.PENDING -> Icons.Filled.HourglassEmpty
            FederationProposal.Status.ACCEPTED -> Icons.Filled.CheckCircle
            null -> Icons.AutoMirrored.Filled.HelpOutline
            else -> Icons.Filled.Clear
        }, tint = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ProposalDetailSheet(proposal: ProposalView, onDismiss: () -> Unit, onChange: (FederationChange) -> Unit) {
    val context = LocalContext.current
    val idLabel = stringResource(R.string.adm_fed_node_id)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.brand.card,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Identicon(proposal.nodeId, size = 40.dp)
                Text(stringResource(R.string.adm_fed_proposal_details), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.adm_done)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) { MonoText(proposal.nodeId) }
                IconButton(onClick = { copyToClipboard(context, idLabel, proposal.nodeId) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.adm_fed_copy_id))
                }
            }
            ProposalBadges(proposal)
            proposal.note?.takeIf { it.isNotBlank() }?.let { FederationDetailField(stringResource(R.string.adm_fed_detail_note), it) }
            if (proposal.createdAt > 0) Text(stringResource(R.string.adm_fed_created, context.date(proposal.createdAt)))
            if (proposal.updatedAt > 0) Text(stringResource(R.string.adm_fed_updated, context.date(proposal.updatedAt)))
            if (proposal.answeredAt > 0) Text(stringResource(R.string.adm_fed_answered, context.date(proposal.answeredAt)))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (proposal.canAnswer) {
                    OutlinedButton(onClick = { onChange(FederationChange.AnswerProposal(proposal, true)) }) {
                        Text(stringResource(R.string.adm_fed_accept))
                    }
                    TextButton(onClick = { onChange(FederationChange.AnswerProposal(proposal, false)) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.adm_fed_decline))
                    }
                }
                if (proposal.canRemove) TextButton(onClick = { onChange(FederationChange.RemoveProposal(proposal)) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(if (proposal.status == FederationProposal.Status.PENDING)
                        R.string.adm_fed_withdraw_proposal else R.string.adm_fed_remove_proposal))
                }
            }
        }
    }
}

@Composable
private fun proposalDirection(role: FederationProposal.Role?): String = stringResource(when (role) {
    FederationProposal.Role.OFFER -> R.string.adm_fed_made
    FederationProposal.Role.ANSWER -> R.string.adm_fed_received
    null -> R.string.adm_fed_unknown
})

@Composable
private fun proposalStatus(status: FederationProposal.Status?): String = stringResource(when (status) {
    FederationProposal.Status.PENDING -> R.string.adm_fed_status_pending
    FederationProposal.Status.ACCEPTED -> R.string.adm_fed_status_accepted
    FederationProposal.Status.DECLINED -> R.string.adm_fed_status_declined
    FederationProposal.Status.WITHDRAWN -> R.string.adm_fed_status_withdrawn
    FederationProposal.Status.EXPIRED -> R.string.adm_fed_status_expired
    null -> R.string.adm_fed_unknown
})
