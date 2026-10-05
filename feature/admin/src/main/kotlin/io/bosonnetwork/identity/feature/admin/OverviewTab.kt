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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/** Overview: the node's status, and the counts of what it holds. */
@Composable
internal fun OverviewTab(refreshes: Int, viewModel: OverviewViewModel = hiltViewModel()) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    RefreshOn(refreshes, viewModel::refresh)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LoadedContent(content, onRetry = viewModel::refresh) { overview ->
                NodeCard(overview)
                SectionTitle(stringResource(R.string.adm_overview_counts))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Count(stringResource(R.string.adm_overview_users), overview.users.toString(), Modifier.weight(1f))
                    Count(
                        stringResource(R.string.adm_overview_plans),
                        stringResource(R.string.adm_overview_plans_value, overview.activePlans, overview.plans),
                        Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Count(
                        stringResource(R.string.adm_overview_federated),
                        overview.federatedNodes?.toString() ?: stringResource(R.string.adm_overview_federation_off),
                        Modifier.weight(1f),
                    )
                    Count(stringResource(R.string.adm_overview_blacklisted), overview.blacklisted.toString(), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NodeCard(overview: NodeOverview) {
    SectionCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Identicon(overview.nodeId, size = 48.dp)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(overview.name ?: stringResource(R.string.adm_overview_unnamed), style = MaterialTheme.typography.titleMedium)
                IdText(overview.nodeId, short = true, style = MaterialTheme.typography.bodyMedium)
            }
        }
        StatusLine(
            if (overview.running) Status.OK else Status.PROBLEM,
            stringResource(if (overview.running) R.string.adm_overview_running else R.string.adm_overview_stopped),
            overview.startedAt.takeIf { it > 0 }?.let { stringResource(R.string.adm_overview_started, relative(it)) },
        )
        listOfNotNull(overview.software, overview.version).joinToString(" ").takeIf { it.isNotEmpty() }?.let {
            Field(stringResource(R.string.adm_overview_software), it)
        }
        if (overview.services.isNotEmpty()) {
            Field(stringResource(R.string.adm_overview_services), overview.services.joinToString(", "))
        }
    }
}

@Composable
internal fun Field(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Count(label: String, value: String, modifier: Modifier) {
    SectionCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.tertiary)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
