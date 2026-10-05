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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.identity.core.qr.rememberQrBitmap

/**
 * Moving the identity here from the old phone: choosing the home node, then showing the pairing code
 * for the old phone to approve in Devices. [onDone] gets the identity's id once it is on this phone,
 * already one of its devices at the node; [onCancel] leaves.
 */
@Composable
fun MoveFromOldPhoneScreen(
    onDone: (userId: String) -> Unit,
    onCancel: () -> Unit,
    viewModel: MoveFromOldPhoneViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finishedUserId) { state.finishedUserId?.let(onDone) }
    BackHandler(enabled = state.step != MoveStep.ADDRESS && state.step != MoveStep.STORING) { viewModel.backToAddress() }

    StepColumn {
        when (state.step) {
            MoveStep.ADDRESS -> {
                StepTitle(stringResource(R.string.onb_move_title))
                StepText(stringResource(R.string.onb_move_body))
                NodeAddressFields(
                    address = state.address,
                    nodeId = state.nodeIdInput,
                    busy = false,
                    onAddressChange = viewModel::onAddressChange,
                    onNodeIdChange = viewModel::onNodeIdChange,
                    onScan = viewModel::scan,
                    onCheck = viewModel::check,
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.onb_back)) }
            }

            MoveStep.SCAN -> NodeScanStep(onScanned = viewModel::onScanned, onCancel = viewModel::backToAddress)

            MoveStep.CONNECTING, MoveStep.STORING -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                StepText(stringResource(if (state.step == MoveStep.CONNECTING) R.string.onb_move_connecting else R.string.onb_move_storing))
            }

            MoveStep.PAIRING -> {
                val code = state.pairingCode.orEmpty()
                StepTitle(stringResource(R.string.onb_move_pair_title))
                StepText(stringResource(R.string.onb_move_pair_body))
                rememberQrBitmap(code)?.let {
                    Image(it, contentDescription = stringResource(R.string.onb_move_pair_qr), modifier = Modifier.size(260.dp))
                }
                Spacer(Modifier.height(12.dp))
                StepText(stringResource(R.string.onb_move_pair_text))
                SelectionContainer {
                    Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                StepText(stringResource(R.string.onb_move_pair_waiting))
                TextButton(onClick = viewModel::backToAddress) { Text(stringResource(R.string.onb_cancel)) }
            }

            MoveStep.STORE_FAILED -> {
                StepTitle(stringResource(R.string.onb_move_store_failed_title))
                StepText(stringResource(R.string.onb_move_store_failed_body))
                Button(onClick = viewModel::tryAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_move_try_again)) }
            }

            MoveStep.DENIED, MoveStep.EXPIRED -> {
                StepTitle(stringResource(if (state.step == MoveStep.DENIED) R.string.onb_move_denied_title else R.string.onb_move_expired_title))
                StepText(stringResource(if (state.step == MoveStep.DENIED) R.string.onb_move_denied_body else R.string.onb_move_expired_body))
                Button(onClick = viewModel::tryAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_move_try_again)) }
                TextButton(onClick = viewModel::backToAddress) { Text(stringResource(R.string.onb_join_other_node)) }
            }
        }
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}
