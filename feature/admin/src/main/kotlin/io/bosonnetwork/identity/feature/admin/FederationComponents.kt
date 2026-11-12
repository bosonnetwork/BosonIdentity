/*
 * Copyright (c) 2026 -      bosonnetwork.io
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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.bosonnetwork.identity.core.designsystem.theme.brand

/** A compact selector beside the current view's search, with room for larger accessibility text. */
@Composable
internal fun FederationToolbar(
    view: FederationView,
    onSelect: (FederationView) -> Unit,
    search: (@Composable (Modifier) -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (search == null) {
            FederationSelector(view, onSelect)
        } else if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FederationSelector(view, onSelect)
                search(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FederationSelector(view, onSelect)
                search(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FederationSelector(view: FederationView, onSelect: (FederationView) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val description = stringResource(R.string.adm_fed_view, federationView(view))
    Box {
        FilledTonalButton(
            onClick = { menu = true },
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Icon(viewIcon(view), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(federationView(view), modifier = Modifier.padding(horizontal = 6.dp))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MaterialTheme.brand.card) {
            FederationView.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(federationView(entry)) },
                    leadingIcon = { Icon(viewIcon(entry), contentDescription = null) },
                    onClick = { menu = false; onSelect(entry) },
                )
            }
        }
    }
}

@Composable
private fun federationView(view: FederationView): String = stringResource(when (view) {
    FederationView.NODES -> R.string.adm_fed_view_nodes
    FederationView.PROPOSALS -> R.string.adm_fed_proposals
})

private fun viewIcon(view: FederationView): ImageVector = when (view) {
    FederationView.NODES -> Icons.Filled.Hub
    FederationView.PROPOSALS -> Icons.Filled.Handshake
}

/** Global operations live above the floating button; row menus remain specific to one record. */
@Composable
internal fun FederationActions(
    nodes: Boolean,
    onAdmit: () -> Unit,
    onSuspend: () -> Unit,
    onPropose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        FloatingActionButton(
            onClick = { menu = true },
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) { Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.adm_fed_actions)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MaterialTheme.brand.card) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.adm_fed_propose)) },
                leadingIcon = { Icon(Icons.Filled.AddLink, contentDescription = null) },
                onClick = { menu = false; onPropose() },
            )
            if (nodes) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.adm_fed_admit_node)) },
                    leadingIcon = { Icon(Icons.Filled.Handshake, contentDescription = null) },
                    onClick = { menu = false; onAdmit() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.adm_fed_suspend_node)) },
                    leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null) },
                    onClick = { menu = false; onSuspend() },
                )
            }
        }
    }
}

/** Non-interactive labels distinguish status dimensions with both an icon and readable text. */
@Composable
internal fun FederationBadge(text: String, icon: ImageVector, tint: Color = Color.Unspecified, description: String = text) {
    val color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.tertiary else tint
    Surface(color = color.copy(alpha = 0.10f), contentColor = color, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description }) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun FederationDetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
