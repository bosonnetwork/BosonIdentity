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

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.bosonnetwork.identity.core.designsystem.component.Status
import io.bosonnetwork.identity.core.designsystem.component.StatusLine
import io.bosonnetwork.identity.core.designsystem.theme.brand

/**
 * Ignores touches while another app's window covers this one, so an overlay can't trick the
 * administrator into tapping a confirmation (tapjacking).
 */
@Composable
internal fun ObscuredTouchGuard() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.filterTouchesWhenObscured
        view.filterTouchesWhenObscured = true
        onDispose { view.filterTouchesWhenObscured = before }
    }
}

@Composable
internal fun ErrorText(error: String?) {
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun Busy() {
    CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.tertiary)
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}

/** A small violet label, such as "Admin" beside a user. */
@Composable
internal fun AdminBadge(text: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(8.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** What a section shows before its content: a progress bar, or a failure to retry. */
@Composable
internal fun <T> LoadedContent(loaded: Loaded<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (loaded) {
        Loaded.Idle -> Unit
        Loaded.Loading -> LinearProgressIndicator(
            Modifier.fillMaxWidth().padding(16.dp),
            color = MaterialTheme.colorScheme.tertiary,
        )
        is Loaded.Failed -> Box(Modifier.padding(vertical = 8.dp)) {
            StatusLine(Status.PROBLEM, stringResource(R.string.adm_load_failed), loaded.message, onRetry)
        }
        is Loaded.Ready -> content(loaded.value)
    }
}

/** Text in monospace, for ids and documents. */
@Composable
internal fun MonoText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = modifier)
}

/** A confirmation for a change that is easily undone, or not destructive. */
@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    action: String,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        containerColor = MaterialTheme.brand.card,
        title = { Text(title) },
        text = {
            ObscuredTouchGuard()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body)
                ErrorText(error)
            }
        },
        confirmButton = {
            if (busy) {
                Busy()
            } else {
                TextButton(
                    onClick = onConfirm,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    ),
                ) { Text(action) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.adm_cancel)) } },
    )
}

/**
 * A confirmation for a destructive change: it takes typing [code] (the first characters of an id, or
 * a name), never a single tap.
 */
@Composable
internal fun ConfirmByTypingDialog(
    title: String,
    body: String,
    code: String,
    action: String,
    busy: Boolean,
    error: String?,
    onConfirm: (typed: String) -> Unit,
    onCancel: () -> Unit,
) {
    var typed by rememberSaveable(code) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        containerColor = MaterialTheme.brand.card,
        title = { Text(title) },
        text = {
            ObscuredTouchGuard()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                TypeToConfirm(code, typed, onTypedChange = { typed = it }, enabled = !busy)
                ErrorText(error)
            }
        },
        confirmButton = {
            if (busy) {
                Busy()
            } else {
                TextButton(
                    onClick = { onConfirm(typed) },
                    enabled = confirms(typed, code),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(action) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.adm_cancel)) } },
    )
}

/** "Type 7Hq3 to confirm", and the field to type it in. */
@Composable
internal fun TypeToConfirm(code: String, typed: String, onTypedChange: (String) -> Unit, enabled: Boolean) {
    Text(stringResource(R.string.adm_type_to_confirm, code))
    OutlinedTextField(
        value = typed,
        onValueChange = onTypedChange,
        label = { Text(stringResource(R.string.adm_type_label)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A date, with the year. */
internal fun Context.date(at: Long): String =
    DateUtils.formatDateTime(this, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)

/** How long ago, or until, [at]. */
internal fun relative(at: Long): String {
    val now = System.currentTimeMillis()
    return DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
}
