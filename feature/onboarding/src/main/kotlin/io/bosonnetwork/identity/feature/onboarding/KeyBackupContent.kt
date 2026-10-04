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

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bosonnetwork.identity.core.designsystem.component.SecureScreen
import io.bosonnetwork.identity.core.designsystem.component.groupedId
import io.bosonnetwork.identity.core.qr.rememberQrBitmap
import io.bosonnetwork.identity.core.vault.KeyBackup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A centered, scrolling column no wider than a phone, for the onboarding and backup steps. */
@Composable
internal fun StepColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
internal fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
}

@Composable
internal fun StepText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
}

/**
 * Saving the key, in any of three ways: the key file, the QR code, or the text. The screen is kept out
 * of screenshots and the recent-apps preview while the key is on it.
 */
@Composable
fun KeyBackupContent(keyBase58: String, keyFileText: () -> String?, onContinue: () -> Unit) {
    SecureScreen()
    val context = LocalContext.current
    var shown by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) saved = writeKeyFile(context, uri, keyFileText() ?: return@rememberLauncherForActivityResult)
    }

    StepColumn {
        StepTitle(stringResource(R.string.onb_backup_title))
        StepText(stringResource(R.string.onb_backup_body))

        OutlinedButton(onClick = { saveFile.launch(KeyBackup.FILE_NAME) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Save, contentDescription = null)
            Text(stringResource(R.string.onb_backup_save_file), Modifier.padding(start = 8.dp))
        }
        if (saved) {
            Text(
                stringResource(R.string.onb_backup_file_saved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { shown = if (shown == "qr") null else "qr" }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.QrCode2, contentDescription = null)
            Text(stringResource(R.string.onb_backup_show_qr), Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { shown = if (shown == "text") null else "text" }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.TextFields, contentDescription = null)
            Text(stringResource(R.string.onb_backup_show_text), Modifier.padding(start = 8.dp))
        }

        when (shown) {
            "qr" -> rememberQrBitmap(keyBase58)?.let {
                Spacer(Modifier.height(16.dp))
                Image(it, contentDescription = stringResource(R.string.onb_backup_qr_description), modifier = Modifier.size(240.dp))
            }
            "text" -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    groupedId(keyBase58),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = {
                    copySensitive(context, keyBase58)
                    copied = true
                    scope.launch {
                        delay(CLIPBOARD_SECONDS * 1000L)
                        clearClipboard(context)
                        copied = false
                    }
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Text(
                        if (copied) stringResource(R.string.onb_backup_copied, CLIPBOARD_SECONDS)
                        else stringResource(R.string.onb_backup_copy),
                        Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.onb_backup_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onb_backup_continue))
        }
    }
}

/**
 * Checking the backup: two groups of the key, by their character positions. Nobody has to retype all
 * of it, but nobody gets through without a copy either.
 */
@Composable
fun KeyVerifyContent(
    keyBase58: String,
    challenge: KeyBackup.Challenge,
    failed: Boolean,
    onVerify: (String, String) -> Unit,
    onShowKeyAgain: () -> Unit,
    onSkip: (() -> Unit)?,
) {
    var first by rememberSaveable(challenge) { mutableStateOf("") }
    var second by rememberSaveable(challenge) { mutableStateOf("") }
    StepColumn {
        StepTitle(stringResource(R.string.onb_verify_title))
        StepText(stringResource(R.string.onb_verify_body))
        val r1 = challenge.range(challenge.first, keyBase58)
        val r2 = challenge.range(challenge.second, keyBase58)
        GroupField(stringResource(R.string.onb_verify_group, r1.first, r1.last), first) { first = it }
        Spacer(Modifier.height(8.dp))
        GroupField(stringResource(R.string.onb_verify_group, r2.first, r2.last), second) { second = it }
        if (failed) {
            Text(
                stringResource(R.string.onb_verify_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onVerify(first, second) },
            enabled = first.isNotBlank() && second.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.onb_verify_check)) }
        TextButton(onClick = onShowKeyAgain) { Text(stringResource(R.string.onb_verify_show_again)) }
        if (onSkip != null) {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.onb_verify_skip)) }
        }
    }
}

@Composable
private fun GroupField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(8)) },
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        // Base58 is case-sensitive: no auto-capitalization, no suggestions that could learn the key.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val CLIPBOARD_SECONDS = 60

private fun writeKeyFile(context: Context, uri: Uri, text: String): Boolean =
    runCatching {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }.isSuccess

// Marked sensitive, so the system's clipboard preview doesn't show it; cleared after a minute.
private fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText("Boson key", text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

private fun clearClipboard(context: Context) {
    context.getSystemService(ClipboardManager::class.java)?.clearPrimaryClip()
}
