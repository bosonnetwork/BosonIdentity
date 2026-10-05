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

package io.bosonnetwork.identity.core.designsystem.component

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.bosonnetwork.identity.core.designsystem.R
import io.bosonnetwork.identity.core.designsystem.theme.brand

/** The fewest characters a new passphrase may have; the Director refuses a shorter one. */
const val PASSPHRASE_MIN_LENGTH = 8

/** The fewest different characters a new passphrase may have; the Director refuses one with fewer. */
const val PASSPHRASE_MIN_DISTINCT = 5

/** Why the Director would refuse a new passphrase. */
enum class PassphraseProblem {
    /** Shorter than [PASSPHRASE_MIN_LENGTH] characters. */
    TOO_SHORT,

    /** Made of fewer than [PASSPHRASE_MIN_DISTINCT] different characters. */
    TOO_PLAIN,
}

/**
 * What is wrong with a new [passphrase], as the Director judges it, or null if it would be accepted.
 * Characters are code points, as on the Director.
 */
fun passphraseProblem(passphrase: String): PassphraseProblem? {
    val codePoints = passphrase.codePoints().toArray()
    return when {
        codePoints.size < PASSPHRASE_MIN_LENGTH -> PassphraseProblem.TOO_SHORT
        codePoints.distinct().size < PASSPHRASE_MIN_DISTINCT -> PassphraseProblem.TOO_PLAIN
        else -> null
    }
}

/** The reason, for the user, why a new passphrase would be refused. */
@Composable
fun PassphraseProblem.text(): String = stringResource(
    when (this) {
        PassphraseProblem.TOO_SHORT -> R.string.ds_passphrase_too_short
        PassphraseProblem.TOO_PLAIN -> R.string.ds_passphrase_too_plain
    },
)

/**
 * Shows new passphrase recovery codes, once: the node keeps only their hashes. Each resets a forgotten
 * passphrase once, without the identity's key, so they are kept apart from it. The user copies or
 * shares them, and the dialog closes only after "I saved these codes"; until then, neither Back nor a
 * tap outside closes it.
 */
@Composable
fun RecoveryCodesDialog(codes: List<String>, userId: String, onDone: () -> Unit) {
    var saved by rememberSaveable(codes) { mutableStateOf(false) }
    val context = LocalContext.current
    val title = stringResource(R.string.ds_recovery_codes_title)
    val text = stringResource(R.string.ds_recovery_codes_text, userId, codes.joinToString("\n"))
    var copied by rememberSaveable(codes) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (saved) onDone() },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = MaterialTheme.brand.card,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ds_recovery_codes_body))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        codes.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        copySensitive(context, title, text)
                        copied = true
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Text(
                            stringResource(if (copied) R.string.ds_recovery_codes_copied else R.string.ds_action_copy),
                            Modifier.padding(start = 6.dp),
                        )
                    }
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, title)
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, title))
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Text(stringResource(R.string.ds_action_share), Modifier.padding(start = 6.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(value = saved, role = Role.Checkbox, onValueChange = { saved = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = saved, onCheckedChange = null)
                    Text(stringResource(R.string.ds_recovery_codes_saved), Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDone, enabled = saved) { Text(stringResource(R.string.ds_action_done)) }
        },
    )
}

// Marked sensitive, so the system's clipboard preview doesn't show the codes.
private fun copySensitive(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}
