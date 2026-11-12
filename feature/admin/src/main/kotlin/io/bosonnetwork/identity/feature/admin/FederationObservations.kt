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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.director.client.FederatedHost
import io.bosonnetwork.director.client.FederatedNodeHealth
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.Identicon
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.designsystem.component.copyToClipboard
import io.bosonnetwork.identity.core.designsystem.theme.brand
import io.bosonnetwork.service.FederationContext.Outcome

@Composable
internal fun FederationActivityPane(refreshes: Int, changes: Int, actions: FederationViewModel,
    viewModel: FederationActivityViewModel = hiltViewModel()) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val pages = (content as? Loaded.Ready)?.value
    LaunchedEffect(pages) {
        if (pages != null && pages.items.none { it.nodeId.toString() == selectedId }) selectedId = null
    }
    ObservationPane(FederationView.ACTIVITY, refreshes, changes, actions, viewModel, key = { it.nodeId.toString() }) { health ->
        ActivityRow(health, onClick = { selectedId = health.nodeId.toString() },
            onPropose = { actions.startActivityProposal(health) })
    }
    pages?.items?.firstOrNull { it.nodeId.toString() == selectedId }?.let { health ->
        ActivityDetailSheet(health, onDismiss = { selectedId = null }, onPropose = {
            selectedId = null
            actions.startActivityProposal(health)
        })
    }
}

@Composable
internal fun FederationHostsPane(refreshes: Int, changes: Int, actions: FederationViewModel,
    viewModel: FederationHostsViewModel = hiltViewModel()) {
    ObservationPane(FederationView.HOSTS, refreshes, changes, actions, viewModel,
        key = { it.host.orElse(UNRESOLVED_HOST_QUERY) }, onUnresolved = viewModel::showUnresolved) { host -> HostRow(host) }
}

/** The two local observation lists share presentation and paging, with independent view models. */
@Composable
private fun <T : Any> ObservationPane(view: FederationView, refreshes: Int, changes: Int, actions: FederationViewModel,
    viewModel: FederationObservationViewModel<T>, key: (T) -> String, onUnresolved: (() -> Unit)? = null,
    row: @Composable (T) -> Unit) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val review by actions.review.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var info by remember { mutableStateOf(false) }
    val hosts = view == FederationView.HOSTS
    val title = stringResource(if (hosts) R.string.adm_fed_hosts else R.string.adm_fed_activity)
    RefreshOn(refreshes, viewModel::refreshList)
    RefreshOn(changes, viewModel::refreshList)
    LaunchedEffect(state.appliedQuery) { list.scrollToItem(0) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            FederationToolbar(view, actions::selectView, review, actions::reviewPending, actions::refreshReviewCount,
                error = if (state.invalid) stringResource(R.string.adm_fed_invalid_id) else null) { modifier ->
                val description = stringResource(if (hosts) R.string.adm_fed_search_hosts else R.string.adm_fed_search_nodes)
                OutlinedTextField(value = state.query, onValueChange = viewModel::onQueryChange,
                    placeholder = { Text(stringResource(if (hosts) R.string.adm_fed_host else R.string.adm_fed_node_id)) },
                    trailingIcon = if (state.query.isNotEmpty()) {
                        { IconButton(onClick = viewModel::clearSearch) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.adm_fed_clear_search)) } }
                    } else null,
                    singleLine = true, isError = state.invalid, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = modifier.semantics { contentDescription = description })
            }
            val pages = (content as? Loaded.Ready)?.value
            if (pages?.enabled == false) {
                SectionCard(Modifier.padding(16.dp)) { Text(stringResource(R.string.adm_fed_off), modifier = Modifier.padding(16.dp)) }
            } else if (state.pending) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.tertiary)
            } else if (!state.invalid) LoadedContent(content, onRetry = viewModel::refreshList) { loaded ->
                LazyColumn(Modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(if (hosts) R.string.adm_fed_hosts_count else R.string.adm_fed_activity_count,
                                loaded.items.size, loaded.total), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            IconButton(onClick = { info = true }) {
                                Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.adm_fed_observations_info))
                            }
                        }
                        if (onUnresolved != null) FilterChip(selected = state.appliedQuery == UNRESOLVED_HOST_QUERY,
                            onClick = onUnresolved, label = { Text(stringResource(R.string.adm_fed_unresolved_host)) },
                            modifier = Modifier.padding(horizontal = 16.dp))
                        if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                    if (loaded.items.isEmpty()) item {
                        Text(stringResource(when {
                            hosts && state.appliedQuery.isEmpty() -> R.string.adm_fed_no_hosts
                            hosts -> R.string.adm_fed_no_matching_hosts
                            state.appliedQuery.isEmpty() -> R.string.adm_fed_no_activity
                            else -> R.string.adm_fed_no_matching_activity
                        }), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    }
                    items(loaded.items, key = key) { row(it) }
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
    if (info) AlertDialog(onDismissRequest = { info = false }, containerColor = MaterialTheme.brand.card,
        title = { Text(title) },
        text = { Text(stringResource(if (hosts) R.string.adm_fed_hosts_info else R.string.adm_fed_activity_info),
            modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { info = false }) { Text(stringResource(R.string.adm_done)) } })
}

@Composable
internal fun ActivityRow(health: FederatedNodeHealth, onClick: () -> Unit, onPropose: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button,
        onClickLabel = stringResource(R.string.adm_fed_activity_details), onClick = onClick)
        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Identicon(health.nodeId.toString(), size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IdText(health.nodeId.toString(), modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
                FederationHealthIndicator(health)
            }
            ActivityBadges(health)
            Text(if (health.lastSeenAt > 0) stringResource(R.string.adm_fed_last_seen, relative(health.lastSeenAt))
                else stringResource(R.string.adm_fed_unobserved), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (health.isProposable) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.adm_fed_activity_actions)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MaterialTheme.brand.card) {
                DropdownMenuItem(text = { Text(stringResource(R.string.adm_fed_propose)) }, onClick = { menu = false; onPropose() })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityBadges(health: FederatedNodeHealth) {
    val success = health.lastHour[Outcome.SUCCESS] ?: 0
    val failures = health.lastHour.filterKeys { it != Outcome.SUCCESS }.values.sum()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FederationBadge(stringResource(R.string.adm_fed_hour_success, success), Icons.Filled.CheckCircle,
            tint = if (success > 0) MaterialTheme.brand.success else MaterialTheme.colorScheme.onSurfaceVariant)
        FederationBadge(stringResource(R.string.adm_fed_hour_failures, failures), Icons.Filled.ErrorOutline,
            tint = if (failures > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (health.demand > 0) FederationBadge(stringResource(R.string.adm_fed_demand_count, health.demand), Icons.Filled.HourglassEmpty)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityDetailSheet(health: FederatedNodeHealth, onDismiss: () -> Unit, onPropose: () -> Unit) {
    val context = LocalContext.current
    val id = health.nodeId.toString()
    val idLabel = stringResource(R.string.adm_fed_node_id)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.brand.card,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Identicon(id, size = 40.dp)
                Text(stringResource(R.string.adm_fed_activity_details), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.adm_done)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) { MonoText(id) }
                IconButton(onClick = { copyToClipboard(context, idLabel, id) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.adm_fed_copy_id))
                }
            }
            FederationDetailField(stringResource(R.string.adm_fed_detail_health), healthDescription(health))
            if (health.lastSeenAt > 0) Text(stringResource(R.string.adm_fed_last_seen, relative(health.lastSeenAt)))
            if (health.lastSuccessAt > 0) Text(stringResource(R.string.adm_fed_last_success, relative(health.lastSuccessAt)))
            if (health.lastFailureAt > 0) Text(stringResource(R.string.adm_fed_last_failure, relative(health.lastFailureAt)))
            if (health.lastFailureAt > 0) FederationDetailField(stringResource(R.string.adm_fed_last_outcome), outcomeLabel(health.lastFailure.orElse(null)))
            health.lastFailureDetail.orElse(null)?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
            SectionTitle(stringResource(R.string.adm_fed_last_hour))
            if (health.lastHour.isEmpty()) Text(stringResource(R.string.adm_fed_no_exchanges))
            health.lastHour.forEach { (outcome, count) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(outcomeLabel(outcome), modifier = Modifier.weight(1f))
                    Text(count.toString())
                }
            }
            FederationDetailField(stringResource(R.string.adm_fed_demand), health.demand.toString())
            if (health.lastDemandAt > 0) Text(stringResource(R.string.adm_fed_last_demand, relative(health.lastDemandAt)))
            if (health.demand > 0) FederationDetailField(stringResource(R.string.adm_fed_demand_reason), admissionReason(health.lastDemandReason.orElse(null)))
            if (health.isProposable) OutlinedButton(onClick = onPropose) { Text(stringResource(R.string.adm_fed_propose)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HostRow(host: FederatedHost) {
    val address = host.host.orElse(null)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(if (address == null) Icons.AutoMirrored.Filled.HelpOutline else Icons.Filled.Dns,
            contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectionContainer { Text(address ?: stringResource(R.string.adm_fed_unresolved_host),
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (address == null) FontFamily.Default else FontFamily.Monospace)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FederationBadge(stringResource(R.string.adm_fed_host_verified, host.verifiedNodes), Icons.Filled.CheckCircle,
                    tint = if (host.verifiedNodes > 0) MaterialTheme.brand.success else MaterialTheme.colorScheme.onSurfaceVariant)
                FederationBadge(stringResource(R.string.adm_fed_host_failed, host.failedNodes), Icons.Filled.ErrorOutline,
                    tint = if (host.failedNodes > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                FederationBadge(stringResource(R.string.adm_fed_host_not_federating, host.notFederatingNodes), Icons.Filled.PublicOff,
                    tint = if (host.notFederatingNodes > 0) MaterialTheme.brand.warning else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.adm_fed_host_checked, if (host.lastCheckedAt > 0) relative(host.lastCheckedAt)
                else stringResource(R.string.adm_fed_unknown)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun outcomeLabel(outcome: Outcome?): String = stringResource(when (outcome) {
    Outcome.SUCCESS -> R.string.adm_fed_outcome_success
    Outcome.TIMEOUT -> R.string.adm_fed_outcome_timeout
    Outcome.UNREACHABLE -> R.string.adm_fed_outcome_unreachable
    Outcome.REFUSED -> R.string.adm_fed_outcome_refused
    Outcome.MALFORMED_REQUEST -> R.string.adm_fed_outcome_malformed_request
    Outcome.MALFORMED_RESPONSE -> R.string.adm_fed_outcome_malformed_response
    Outcome.ERROR -> R.string.adm_fed_outcome_error
    null -> R.string.adm_fed_unknown
})
