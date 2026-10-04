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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Backing up an identity already on this phone; [onDone] runs once the backup is checked. */
@Composable
fun BackupScreen(onDone: () -> Unit, onCancel: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    BackHandler(enabled = state.step == BackupStep.VERIFY) { viewModel.backToBackup() }

    when (state.step) {
        BackupStep.UNLOCK -> StepColumn {
            StepTitle(stringResource(R.string.onb_backup_title))
            StepText(stringResource(R.string.onb_backup_unlock_body))
            if (state.busy) {
                CircularProgressIndicator()
            } else {
                Button(onClick = viewModel::unlock, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onb_backup_unlock))
                }
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.onb_cancel)) }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
        BackupStep.BACKUP -> KeyBackupContent(
            keyBase58 = state.keyBase58.orEmpty(),
            keyFileText = viewModel::keyFileText,
            onContinue = viewModel::startVerify,
        )
        BackupStep.VERIFY -> state.challenge?.let { challenge ->
            KeyVerifyContent(
                keyBase58 = state.keyBase58.orEmpty(),
                challenge = challenge,
                failed = state.verifyFailed,
                onVerify = viewModel::verify,
                onShowKeyAgain = viewModel::backToBackup,
                onSkip = null,
            )
        }
    }
}
