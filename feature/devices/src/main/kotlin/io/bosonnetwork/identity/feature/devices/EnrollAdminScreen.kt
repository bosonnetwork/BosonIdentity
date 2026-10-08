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

package io.bosonnetwork.identity.feature.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.SectionCard

/**
 * Enrolling the identity as an administrator of a node, from the code its console shows: what it means,
 * the number to type on the console, and how it ended. [onClose] leaves; [onBackup] opens the key's
 * backup, which enrolling needs first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollAdminScreen(
    onClose: () -> Unit,
    onBackup: (userId: String) -> Unit,
    viewModel: EnrollAdminViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObscuredTouchGuard()
    val host = state.node?.hostName().orEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dev_enroll_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dev_close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val close: @Composable () -> Unit = {
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_close)) }
                }
                when (state.step) {
                    EnrollStep.REVIEW -> Review(state, host, viewModel, onClose, onBackup)
                    EnrollStep.CLAIMING -> {
                        Spacer(Modifier.height(48.dp))
                        CircularProgressIndicator()
                    }
                    EnrollStep.NUMBER -> Number(state)
                    EnrollStep.DONE -> Outcome(
                        stringResource(R.string.dev_enroll_done_title, host),
                        stringResource(R.string.dev_enroll_done_body),
                        success = true,
                    ) { Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_done)) } }
                    EnrollStep.DENIED -> Outcome(
                        stringResource(R.string.dev_enroll_denied_title),
                        stringResource(R.string.dev_enroll_denied_body),
                    ) { close() }
                    EnrollStep.TAKEN -> Outcome(
                        stringResource(R.string.dev_enroll_taken_title),
                        stringResource(R.string.dev_enroll_taken_body),
                    ) { close() }
                    EnrollStep.EXPIRED -> Outcome(
                        stringResource(R.string.dev_enroll_expired_title),
                        stringResource(R.string.dev_enroll_expired_body),
                    ) { close() }
                    EnrollStep.OTHER_NODE -> Outcome(
                        stringResource(R.string.dev_enroll_other_node_title),
                        stringResource(
                            R.string.dev_enroll_other_node_body,
                            state.identity?.homeNode?.hostName().orEmpty(),
                            host,
                        ),
                    ) { close() }
                    EnrollStep.ERROR -> Outcome(stringResource(R.string.dev_enroll_error_title), state.error.orEmpty()) {
                        Button(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_approve_retry)) }
                        TextButton(onClick = onClose) { Text(stringResource(R.string.dev_close)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Review(
    state: EnrollUiState,
    host: String,
    viewModel: EnrollAdminViewModel,
    onClose: () -> Unit,
    onBackup: (String) -> Unit,
) {
    val identity = state.identity ?: return
    Text(stringResource(R.string.dev_enroll_review_title, host), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Administrative, so in the brand's violet like everything administrative.
            Text(host, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
            state.node?.nodeId?.let { IdText(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                stringResource(if (identity.homeNode == null) R.string.dev_enroll_body_new else R.string.dev_enroll_body_member, host),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.dev_enroll_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    Spacer(Modifier.height(16.dp))
    WhoWhere(identity, "")
    state.error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(20.dp))
    if (!identity.backupVerified) {
        StepText(stringResource(R.string.dev_enroll_backup_first))
        Button(onClick = { onBackup(identity.userId) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dev_backup_action))
        }
    } else {
        Button(onClick = viewModel::claim, enabled = state.canClaim, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dev_enroll_continue))
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onClose) { Text(stringResource(R.string.dev_cancel)) }
}

@Composable
private fun Number(state: EnrollUiState) {
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.dev_enroll_number_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    Text(
        state.number?.toString().orEmpty(),
        fontSize = 72.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.tertiary,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.dev_approve_expires_in, countdown(state.remainingSeconds)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.dev_enroll_waiting), style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(12.dp))
    StepText(stringResource(R.string.dev_enroll_keep_open))
}
