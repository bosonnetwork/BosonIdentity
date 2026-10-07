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

package io.bosonnetwork.identity

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.Identicon
import io.bosonnetwork.identity.core.designsystem.theme.brand
import io.bosonnetwork.identity.core.model.IdentityInfo
import kotlinx.coroutines.launch

/** The identity in use, as its identicon at the start of the top bar; it opens the switcher. */
@Composable
fun IdentityButton(identity: IdentityInfo?, onClick: () -> Unit) {
    val label = stringResource(R.string.app_switch_identity)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        identity?.let { Identicon(it.userId, size = 32.dp) }
    }
}

/**
 * The identities on this phone, the one in use checked, and adding another. Choosing one makes it
 * the identity in use; the sheet closes before [onSelect] or [onAdd] takes effect on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentitySwitcherSheet(
    identities: List<IdentityInfo>,
    activeUserId: String?,
    onSelect: (userId: String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: (then: () -> Unit) -> Unit = { then ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.brand.card) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.app_identities_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            identities.forEach { identity ->
                val active = identity.userId == activeUserId
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
                    leadingContent = { Identicon(identity.userId, size = 40.dp) },
                    headlineContent = { Text(identity.name ?: stringResource(R.string.app_identity_unnamed)) },
                    supportingContent = {
                        Column {
                            IdText(identity.userId, style = MaterialTheme.typography.bodySmall)
                            Text(
                                identity.homeNode?.let { Uri.parse(it.url).host ?: it.url }
                                    ?: stringResource(R.string.app_identity_no_node),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    trailingContent = if (active) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = stringResource(R.string.app_identity_active),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.selectable(selected = active, role = Role.RadioButton) {
                        close { if (!active) onSelect(identity.userId) }
                    },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ListItem(
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.app_identity_add)) },
                modifier = Modifier.clickable(role = Role.Button) { close(onAdd) },
            )
        }
    }
}
