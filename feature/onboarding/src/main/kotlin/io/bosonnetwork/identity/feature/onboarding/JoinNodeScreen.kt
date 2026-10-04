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

package io.bosonnetwork.identity.feature.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.Identicon
import io.bosonnetwork.identity.core.qr.QrScanner

/**
 * Joining the identity to its home node. [onDone] runs when it joined; [onSkip], when given, lets the
 * user leave the node for later.
 */
@Composable
fun JoinNodeScreen(onDone: () -> Unit, onSkip: (() -> Unit)?, viewModel: JoinNodeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.joined) { if (state.joined) onDone() }
    BackHandler(enabled = state.step == JoinStep.SCAN || state.step == JoinStep.CONFIRM || state.step == JoinStep.PASSPHRASE) {
        viewModel.backToAddress()
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.scan()
    }

    StepColumn {
        when (state.step) {
            JoinStep.ADDRESS -> {
                var advanced by rememberSaveable { mutableStateOf(state.nodeIdInput.isNotEmpty()) }
                StepTitle(stringResource(R.string.onb_join_title))
                StepText(stringResource(R.string.onb_join_body))
                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            viewModel.scan()
                        } else {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.onb_join_scan)) }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = state.address,
                    onValueChange = viewModel::onAddressChange,
                    label = { Text(stringResource(R.string.onb_join_address_label)) },
                    placeholder = { Text("node.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (advanced) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.nodeIdInput,
                        onValueChange = viewModel::onNodeIdChange,
                        label = { Text(stringResource(R.string.onb_join_node_id_label)) },
                        supportingText = { Text(stringResource(R.string.onb_join_node_id_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TextButton(onClick = { advanced = true }) { Text(stringResource(R.string.onb_join_advanced)) }
                }
                Spacer(Modifier.height(8.dp))
                if (state.busy) {
                    CircularProgressIndicator()
                } else {
                    Button(onClick = viewModel::check, enabled = state.address.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.onb_join_check))
                    }
                }
                if (onSkip != null) {
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.onb_join_skip)) }
                }
            }

            JoinStep.SCAN -> {
                StepTitle(stringResource(R.string.onb_join_scan_title))
                QrScanner(onScanned = viewModel::onScanned, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                TextButton(onClick = viewModel::backToAddress) { Text(stringResource(R.string.onb_cancel)) }
            }

            JoinStep.CONFIRM -> state.node?.let { node ->
                StepTitle(stringResource(R.string.onb_join_confirm_title))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    node.nodeId?.let { Identicon(it, size = 48.dp) }
                    Spacer(Modifier.padding(start = 12.dp))
                    androidx.compose.foundation.layout.Column {
                        Text(node.url, style = MaterialTheme.typography.titleMedium)
                        node.nodeId?.let { IdText(it, short = true, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                StepText(
                    stringResource(
                        if (state.acceptsNewAccounts) R.string.onb_join_confirm_body else R.string.onb_join_confirm_body_members_only,
                    ),
                )
                if (state.acceptsNewAccounts) {
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = viewModel::onNameChange,
                        label = { Text(stringResource(R.string.onb_join_name_label)) },
                        supportingText = { Text(stringResource(R.string.onb_join_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Button(onClick = viewModel::join, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onb_join_action))
                }
                TextButton(onClick = viewModel::backToAddress) { Text(stringResource(R.string.onb_join_other_node)) }
            }

            JoinStep.WORKING -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                StepTitle(stringResource(R.string.onb_join_working_title))
                StepText(stringResource(R.string.onb_join_working_body))
            }

            JoinStep.PASSPHRASE -> {
                StepTitle(stringResource(R.string.onb_join_passphrase_title))
                StepText(stringResource(R.string.onb_join_passphrase_body))
                OutlinedTextField(
                    value = state.passphrase,
                    onValueChange = viewModel::onPassphraseChange,
                    label = { Text(stringResource(R.string.onb_join_passphrase_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = viewModel::join, enabled = state.passphrase.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onb_join_action))
                }
            }
        }
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}
