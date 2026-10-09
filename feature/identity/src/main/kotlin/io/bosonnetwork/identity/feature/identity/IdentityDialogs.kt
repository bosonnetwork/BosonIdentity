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

package io.bosonnetwork.identity.feature.identity

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PublicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.bosonnetwork.identity.core.designsystem.theme.brand

/** Editing the profile: name, bio and email, and the account passphrase when the node needs it. */
@Composable
internal fun EditProfileDialog(
    edit: ProfileEdit,
    onNameChange: (String) -> Unit,
    onBioChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!edit.busy) onCancel() },
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.id_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = edit.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.id_name)) },
                    singleLine = true,
                    enabled = !edit.busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = edit.bio,
                    onValueChange = onBioChange,
                    label = { Text(stringResource(R.string.id_bio)) },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !edit.busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = edit.email,
                    onValueChange = onEmailChange,
                    label = { Text(stringResource(R.string.id_email)) },
                    supportingText = { Text(stringResource(R.string.id_email_private)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                    enabled = !edit.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (edit.needsPassphrase) {
                    Text(stringResource(R.string.id_edit_passphrase_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = edit.passphrase,
                        onValueChange = onPassphraseChange,
                        label = { Text(stringResource(R.string.id_edit_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        singleLine = true,
                        enabled = !edit.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (edit.updatesCard) {
                    Text(
                        stringResource(R.string.id_edit_updates_card),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                edit.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (edit.busy) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                TextButton(onClick = onSave, enabled = !edit.needsPassphrase || edit.passphrase.isNotEmpty()) {
                    Text(stringResource(R.string.id_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !edit.busy) { Text(stringResource(R.string.id_cancel)) }
        },
    )
}

/**
 * Before the card is published, what becomes public; before it stops, what stays out there for a
 * while. Both say it plainly, since neither can be taken back at once.
 */
@Composable
internal fun CardConfirmDialog(confirm: CardConfirm, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val publish = confirm == CardConfirm.PUBLISH
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.brand.card,
        icon = { Icon(if (publish) Icons.Outlined.Public else Icons.Outlined.PublicOff, contentDescription = null) },
        title = { Text(stringResource(if (publish) R.string.id_card_publish_title else R.string.id_card_stop_title)) },
        text = { Text(stringResource(if (publish) R.string.id_card_publish_body else R.string.id_card_stop_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(if (publish) R.string.id_card_publish_action else R.string.id_card_stop_action))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.id_cancel)) } },
    )
}

// ---- The avatar --------------------------------------------------------------------------------

/** What can be done with an avatar the account has: a new photo, or none. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AvatarMenu(onChoose: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.brand.card,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.id_avatar_menu_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.id_avatar_choose)) },
                leadingContent = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = onChoose),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.id_avatar_remove), color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = onRemove),
            )
        }
    }
}

/** The picked picture as it will show, to confirm before it goes to the node. */
@Composable
internal fun AvatarConfirmDialog(pick: AvatarPick, host: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val preview = remember(pick.preview) { pick.preview.asImageBitmap() }
    AlertDialog(
        onDismissRequest = { if (!pick.busy) onCancel() },
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.id_avatar_confirm_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // The shape the app shows avatars in.
                Image(
                    preview,
                    contentDescription = stringResource(R.string.id_avatar),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(160.dp).clip(RoundedCornerShape(32.dp)),
                )
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.id_avatar_confirm_body, host), style = MaterialTheme.typography.bodyMedium)
                pick.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (pick.busy) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.id_avatar_use)) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.id_cancel)) } },
    )
}

/** Why the avatar couldn't be changed. */
@Composable
internal fun AvatarErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.id_avatar_error_title)) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.id_ok)) } },
    )
}
