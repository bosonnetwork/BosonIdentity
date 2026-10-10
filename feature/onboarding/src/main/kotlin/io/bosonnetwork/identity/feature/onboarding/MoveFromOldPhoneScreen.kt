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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.identity.core.designsystem.component.SecureScreen
import io.bosonnetwork.identity.core.qr.QrScanner

/**
 * Moving the identity here from the old phone: scanning the QR code it shows, typing the code it showed
 * before, then, with a home node, adding this phone to it. [onDone] gets the identity's id once it is on
 * this phone; [onCancel] leaves.
 */
@Composable
fun MoveFromOldPhoneScreen(
    onDone: (userId: String) -> Unit,
    onCancel: () -> Unit,
    viewModel: MoveFromOldPhoneViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finishedUserId) { state.finishedUserId?.let(onDone) }
    BackHandler(enabled = state.step == MoveStep.CODE) { viewModel.scanAgain() }
    // The code typed here opens the identity's key: kept off screenshots like the key itself.
    SecureScreen()

    StepColumn {
        when (state.step) {
            MoveStep.SCAN -> {
                StepTitle(stringResource(R.string.onb_move_title))
                StepText(stringResource(R.string.onb_move_body))
                QrScanner(onScanned = viewModel::onScanned, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.onb_back)) }
            }

            MoveStep.CODE -> {
                StepTitle(stringResource(R.string.onb_move_code_title))
                StepText(stringResource(R.string.onb_move_code_body))
                OutlinedTextField(
                    value = state.code,
                    onValueChange = viewModel::onCodeChange,
                    label = { Text(stringResource(R.string.onb_move_code_label)) },
                    placeholder = { Text("XXXX-XXXX-XX") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { viewModel.open() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = viewModel::open, enabled = state.code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onb_move_open))
                }
                TextButton(onClick = viewModel::scanAgain) { Text(stringResource(R.string.onb_move_scan_again)) }
            }

            MoveStep.OPENING, MoveStep.JOINING -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                StepText(stringResource(if (state.step == MoveStep.OPENING) R.string.onb_move_opening else R.string.onb_move_joining))
            }

            MoveStep.PASSPHRASE -> {
                StepTitle(stringResource(R.string.onb_move_passphrase_title))
                StepText(stringResource(R.string.onb_move_passphrase_body))
                OutlinedTextField(
                    value = state.passphrase,
                    onValueChange = viewModel::onPassphraseChange,
                    label = { Text(stringResource(R.string.onb_move_passphrase_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = viewModel::retryJoin, enabled = state.passphrase.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onb_move_continue))
                }
                TextButton(onClick = viewModel::joinLater) { Text(stringResource(R.string.onb_move_join_later)) }
            }

            MoveStep.JOIN_FAILED -> {
                StepTitle(stringResource(R.string.onb_move_join_failed_title))
                StepText(stringResource(R.string.onb_move_join_failed_body))
                Button(onClick = viewModel::retryJoin, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_move_try_again)) }
                TextButton(onClick = viewModel::joinLater) { Text(stringResource(R.string.onb_move_join_later)) }
            }
        }
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}
