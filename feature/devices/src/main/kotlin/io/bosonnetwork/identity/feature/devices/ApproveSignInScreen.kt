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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.director.client.SignInRequest
import io.bosonnetwork.identity.core.designsystem.component.SectionCard

/**
 * Signing a web page in with the identity: what the page signs in to and where, the number to pick, and
 * approving or refusing. [onClose] leaves; [onBackup] opens the key's backup, which approving needs first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApproveSignInScreen(
    onClose: () -> Unit,
    onBackup: (userId: String) -> Unit,
    viewModel: ApproveSignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObscuredTouchGuard()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dev_signin_title)) },
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
                when (state.step) {
                    SignInStep.LOADING, SignInStep.WORKING -> {
                        Spacer(Modifier.height(48.dp))
                        CircularProgressIndicator()
                    }
                    SignInStep.REVIEW -> Review(state, viewModel, onBackup)
                    SignInStep.DONE -> Outcome(
                        stringResource(R.string.dev_signin_done_title),
                        stringResource(R.string.dev_signin_done_body),
                        success = true,
                    ) { Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_done)) } }
                    SignInStep.REFUSED -> Outcome(
                        stringResource(R.string.dev_signin_refused_title),
                        stringResource(if (state.wrongNumber) R.string.dev_signin_wrong_number else R.string.dev_signin_refused_body),
                    ) { Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_close)) } }
                    SignInStep.EXPIRED -> Outcome(
                        stringResource(R.string.dev_signin_expired_title),
                        stringResource(R.string.dev_signin_expired_body),
                    ) { Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_close)) } }
                    SignInStep.ERROR -> Outcome(stringResource(R.string.dev_signin_error_title), state.error.orEmpty()) {
                        Button(onClick = viewModel::load, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_approve_retry)) }
                        TextButton(onClick = onClose) { Text(stringResource(R.string.dev_close)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Review(state: SignInUiState, viewModel: ApproveSignInViewModel, onBackup: (String) -> Unit) {
    val identity = state.identity ?: return
    val admin = state.app == SignInRequest.APP_ADMIN
    val host = identity.homeNode?.hostName().orEmpty()
    Text(stringResource(R.string.dev_signin_review_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (admin) R.string.dev_signin_admin else R.string.dev_signin_portal),
                style = MaterialTheme.typography.titleMedium,
                color = if (admin) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.dev_signin_requested_from, state.requestedFrom ?: stringResource(R.string.dev_detail_unknown)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.dev_signin_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    Spacer(Modifier.height(16.dp))
    WhoWhere(identity, host)
    Spacer(Modifier.height(12.dp))
    Text(
        if (state.expired) stringResource(R.string.dev_signin_expired_now)
        else stringResource(R.string.dev_approve_expires_in, countdown(state.remainingSeconds)),
        style = MaterialTheme.typography.bodySmall,
        color = if (state.expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    Text(stringResource(R.string.dev_signin_pick), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        state.choices.forEach { number ->
            FilterChip(
                selected = state.picked == number,
                onClick = { viewModel.onPick(number) },
                enabled = !state.expired,
                label = { Text(number.toString(), fontSize = 28.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) },
            )
        }
    }
    state.error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(20.dp))
    if (!identity.backupVerified) {
        StepText(stringResource(R.string.dev_signin_backup_first))
        Button(onClick = { onBackup(identity.userId) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dev_backup_action))
        }
    } else {
        Button(onClick = viewModel::approve, enabled = state.canApprove, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dev_signin_approve))
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = viewModel::refuse, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_signin_refuse)) }
}
