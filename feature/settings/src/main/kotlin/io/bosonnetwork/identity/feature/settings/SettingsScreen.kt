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

package io.bosonnetwork.identity.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.identity.core.boson.ThisDevice
import io.bosonnetwork.identity.core.designsystem.component.ActionRow
import io.bosonnetwork.identity.core.designsystem.component.BrandTile
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.Identicon
import io.bosonnetwork.identity.core.designsystem.component.PageColumn
import io.bosonnetwork.identity.core.designsystem.component.Section
import io.bosonnetwork.identity.core.designsystem.component.shortId
import io.bosonnetwork.identity.core.designsystem.theme.brand
import io.bosonnetwork.identity.core.model.AppLanguage
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.model.ThemeMode

/**
 * The Settings tab: how the app looks and speaks, what this phone is to the identity in use (moving to
 * a new phone, removing the identity from this one), and about the app. The theme and the language
 * are the app's: [onThemeChange] applies at once, [onLanguageChange] restarts the app.
 * [onNoIdentityLeft] runs when the last identity was removed. [navigationIcon] goes at the start of
 * the top bar; the app puts its identity switcher there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    theme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onNoIdentityLeft: () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosingTheme by rememberSaveable { mutableStateOf(false) }
    var choosingLanguage by rememberSaveable { mutableStateOf(false) }
    var moving by rememberSaveable { mutableStateOf(false) }
    val identity = state.identity
    // Asked about one identity: once it is gone (or another is picked), the question is closed, so it
    // never carries over to the identity shown next.
    var confirmRemove by rememberSaveable(identity?.userId) { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(state.noIdentityLeft) { if (state.noIdentityLeft) onNoIdentityLeft() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.set_title)) },
                navigationIcon = navigationIcon,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        PageColumn(Modifier.padding(padding)) {
            Section(stringResource(R.string.set_appearance_title)) {
                ActionRow(
                    Icons.Outlined.Palette,
                    stringResource(R.string.set_theme),
                    themeLabel(theme),
                    onClick = { choosingTheme = true },
                )
                ActionRow(
                    Icons.Outlined.Language,
                    stringResource(R.string.set_language),
                    languageLabel(language),
                    onClick = { choosingLanguage = true },
                )
            }
            if (identity != null) {
                Section(stringResource(R.string.set_phone_title)) {
                    ActionRow(
                        Icons.Outlined.PhoneAndroid,
                        ThisDevice.name,
                        identity.deviceId?.let { stringResource(R.string.set_phone_device, shortId(it)) }
                            ?: stringResource(R.string.set_phone_unregistered),
                    )
                    ActionRow(
                        Icons.Outlined.PhonelinkSetup,
                        stringResource(R.string.set_move),
                        stringResource(R.string.set_move_detail),
                        onClick = { moving = true },
                    )
                    ActionRow(
                        Icons.Outlined.DeleteOutline,
                        stringResource(R.string.set_remove),
                        state.removeError ?: stringResource(R.string.set_remove_detail),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { confirmRemove = true },
                    )
                }
            }
            Section(stringResource(R.string.set_about_title)) {
                About()
                ActionRow(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    stringResource(R.string.set_about_website),
                    stringResource(R.string.set_about_website_detail),
                    onClick = { openWebsite(context) },
                )
            }
        }
    }

    if (choosingTheme) {
        ChoiceDialog(
            title = stringResource(R.string.set_theme),
            options = ThemeMode.entries,
            selected = theme,
            label = { themeLabel(it) },
            onSelect = {
                choosingTheme = false
                onThemeChange(it)
            },
            onDismiss = { choosingTheme = false },
        )
    }
    if (choosingLanguage) {
        ChoiceDialog(
            title = stringResource(R.string.set_language),
            options = AppLanguage.entries,
            selected = language,
            label = { languageLabel(it) },
            note = stringResource(R.string.set_language_restart),
            onSelect = {
                choosingLanguage = false
                if (it != language) onLanguageChange(it)
            },
            onDismiss = { choosingLanguage = false },
        )
    }
    if (moving) {
        MoveDialog(host = identity?.homeNode?.let { hostOf(it.url) }, onDismiss = { moving = false })
    }
    if ((confirmRemove || state.removal != null) && identity != null) {
        RemoveDialog(
            identity = identity,
            removal = state.removal,
            onRemove = { typed, passphrase, force -> viewModel.remove(typed, passphrase, force) },
            onCancel = {
                confirmRemove = false
                viewModel.cancelRemoval()
            },
        )
    }
}

@Composable
private fun themeLabel(theme: ThemeMode): String = stringResource(
    when (theme) {
        ThemeMode.SYSTEM -> R.string.set_theme_system
        ThemeMode.LIGHT -> R.string.set_theme_light
        ThemeMode.DARK -> R.string.set_theme_dark
    },
)

// Each language is named in itself, so it is found whatever the app shows now.
@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.set_language_system)
    AppLanguage.ENGLISH -> "English"
    AppLanguage.SIMPLIFIED_CHINESE -> "简体中文"
}

/** The app's name, version and what it is for, beside the Boson mark. */
@Composable
private fun About() {
    val context = LocalContext.current
    val version = remember(context) { context.appVersion() }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandTile(size = 48.dp)
            Column(Modifier.padding(start = 16.dp)) {
                Text(stringResource(R.string.set_about_name), style = MaterialTheme.typography.titleMedium)
                version?.let {
                    Text(
                        stringResource(R.string.set_about_version, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.set_about_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Context.appVersion(): String? =
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()

private fun openWebsite(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE)))
    } catch (e: ActivityNotFoundException) {
        // No browser: nothing to open it with.
    }
}

private const val WEBSITE = "https://bosonnetwork.io"

/** One of [options], picked with radio buttons; picking one closes the dialog. */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    note: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.brand.card,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = option == selected, role = Role.RadioButton) { onSelect(option) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
                note?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.set_cancel)) } },
    )
}

/** How to bring the identity to another phone, step by step. */
@Composable
private fun MoveDialog(host: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.set_move)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.set_move_step1))
                Text(host?.let { stringResource(R.string.set_move_step2_node, it) } ?: stringResource(R.string.set_move_step2))
                Text(stringResource(R.string.set_move_step3))
                Text(stringResource(R.string.set_move_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.set_done)) } },
    )
}

/**
 * Removing the identity from this phone takes typing the first characters of its id, and says
 * plainly when no checked backup exists: then the identity is gone for good.
 */
@Composable
private fun RemoveDialog(
    identity: IdentityInfo,
    removal: Removal?,
    onRemove: (typed: String, passphrase: String?, force: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var typed by rememberSaveable(identity.userId) { mutableStateOf("") }
    var passphrase by rememberSaveable(identity.userId) { mutableStateOf("") }
    val code = removalCode(identity.userId)
    val busy = removal?.busy == true
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.set_remove_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Identicon(identity.userId, size = 40.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(identity.name ?: stringResource(R.string.set_unnamed), style = MaterialTheme.typography.titleSmall)
                        IdText(identity.userId, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (identity.backupVerified) {
                    Text(stringResource(R.string.set_remove_verified))
                } else {
                    Text(stringResource(R.string.set_remove_unverified), color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.set_remove_type, code))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.set_remove_type_label)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (removal?.needsPassphrase == true) {
                    Text(stringResource(R.string.set_remove_passphrase))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(R.string.set_passphrase)) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                removal?.unreachable?.let {
                    Text(stringResource(R.string.set_remove_unreachable, it), color = MaterialTheme.colorScheme.error)
                }
                removal?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val force = removal?.unreachable != null
            TextButton(
                onClick = { onRemove(typed, passphrase.ifEmpty { null }, force) },
                enabled = !busy && typed.trim() == code && (removal?.needsPassphrase != true || passphrase.isNotEmpty()),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(if (force) R.string.set_remove_anyway else R.string.set_remove_action))
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.set_cancel)) } },
    )
}
