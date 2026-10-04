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
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.identity.core.designsystem.component.BrandTile
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.Identicon
import io.bosonnetwork.identity.core.designsystem.component.SecureScreen
import io.bosonnetwork.identity.core.qr.QrScanner

/** Creating or restoring the identity; [onFinished] gets its id once it is sealed in the vault. */
@Composable
fun OnboardingScreen(onFinished: (userId: String) -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finishedUserId) { state.finishedUserId?.let(onFinished) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshCanProtect() }
    BackHandler(enabled = state.step != OnboardingStep.WELCOME) {
        when (state.step) {
            OnboardingStep.VERIFY -> viewModel.backToBackup()
            else -> viewModel.restart()
        }
    }

    when (state.step) {
        OnboardingStep.WELCOME -> Welcome(onCreate = viewModel::create, onRestore = viewModel::startRestore)
        OnboardingStep.CREATED -> Created(userId = state.userId.orEmpty(), onContinue = viewModel::startBackup)
        OnboardingStep.BACKUP -> KeyBackupContent(
            keyBase58 = state.keyBase58.orEmpty(),
            keyFileText = viewModel::keyFileText,
            onContinue = viewModel::startVerify,
        )
        OnboardingStep.VERIFY -> state.challenge?.let { challenge ->
            KeyVerifyContent(
                keyBase58 = state.keyBase58.orEmpty(),
                challenge = challenge,
                failed = state.verifyFailed,
                onVerify = viewModel::verify,
                onShowKeyAgain = viewModel::backToBackup,
                onSkip = viewModel::skipVerify,
            )
        }
        OnboardingStep.RESTORE -> Restore(error = state.error, onRestore = viewModel::restore, onBack = viewModel::restart)
        OnboardingStep.PROTECT -> Protect(
            canProtect = state.canProtect,
            backupVerified = state.backupVerified,
            busy = state.busy,
            error = state.error,
            onProtect = viewModel::protect,
        )
    }
}

@Composable
private fun Welcome(onCreate: () -> Unit, onRestore: () -> Unit) {
    StepColumn {
        BrandTile(size = 88.dp)
        Spacer(Modifier.height(24.dp))
        StepTitle(stringResource(R.string.onb_welcome_title))
        StepText(stringResource(R.string.onb_welcome_body))
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_welcome_create)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_welcome_restore)) }
    }
}

@Composable
private fun Created(userId: String, onContinue: () -> Unit) {
    StepColumn {
        Identicon(userId, size = 72.dp)
        Spacer(Modifier.height(16.dp))
        StepTitle(stringResource(R.string.onb_created_title))
        IdText(userId, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        StepText(stringResource(R.string.onb_created_body))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_created_continue)) }
    }
}

@Composable
private fun Restore(error: String?, onRestore: (String) -> Unit, onBack: () -> Unit) {
    SecureScreen()
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val content = uri?.let { runCatching { context.contentResolver.openInputStream(it)!!.use { s -> s.readBytes().toString(Charsets.UTF_8) } }.getOrNull() }
        if (content != null) onRestore(content)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted
    }

    StepColumn {
        StepTitle(stringResource(R.string.onb_restore_title))
        StepText(stringResource(R.string.onb_restore_body))
        if (scanning) {
            QrScanner(
                onScanned = { scanning = false; onRestore(it) },
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            TextButton(onClick = { scanning = false }) { Text(stringResource(R.string.onb_cancel)) }
        } else {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.onb_restore_paste_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onRestore(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onb_restore_use_key))
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { openFile.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onb_restore_open_file))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        scanning = true
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onb_restore_scan)) }
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) { Text(stringResource(R.string.onb_back)) }
    }
}

@Composable
private fun Protect(canProtect: Boolean, backupVerified: Boolean, busy: Boolean, error: String?, onProtect: () -> Unit) {
    val context = LocalContext.current
    StepColumn {
        StepTitle(stringResource(R.string.onb_protect_title))
        StepText(stringResource(R.string.onb_protect_body))
        if (!backupVerified) {
            Text(
                stringResource(R.string.onb_protect_unverified),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
        }
        if (!canProtect) {
            Text(stringResource(R.string.onb_protect_no_lock), textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onb_protect_open_settings)) }
        } else if (busy) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onProtect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_protect_action)) }
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}
