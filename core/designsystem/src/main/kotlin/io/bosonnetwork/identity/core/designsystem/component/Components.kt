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

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.bosonnetwork.identity.core.designsystem.R
import io.bosonnetwork.identity.core.designsystem.theme.brand

/** The app icon on its dark tile, as on the launcher. */
@Composable
fun BrandTile(modifier: Modifier = Modifier, size: Dp = 72.dp) {
    Image(
        painter = painterResource(R.drawable.ds_app_tile),
        contentDescription = stringResource(R.string.ds_app_name),
        modifier = modifier.size(size),
    )
}

/** A card on the page ground, in the brand's card color. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.brand.card,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/**
 * How a protection is doing: in place, worth a look, wrong, or simply not turned on (an optional
 * protection, which is no warning).
 */
enum class Status { OK, ATTENTION, PROBLEM, OFF }

/** The icon and color of [status], the same on every screen. */
@Composable
fun Status.look(): Pair<ImageVector, Color> = when (this) {
    Status.OK -> Icons.Filled.CheckCircle to MaterialTheme.brand.success
    Status.ATTENTION -> Icons.Filled.Warning to MaterialTheme.brand.warning
    Status.PROBLEM -> Icons.Filled.Error to MaterialTheme.colorScheme.error
    Status.OFF -> Icons.Outlined.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
}

/** What [status] means, in words, for TalkBack and pills without their own label. */
@Composable
fun Status.label(): String = stringResource(
    when (this) {
        Status.OK -> R.string.ds_status_ok
        Status.ATTENTION -> R.string.ds_status_attention
        Status.PROBLEM -> R.string.ds_status_problem
        Status.OFF -> R.string.ds_status_off
    },
)

/**
 * A small rounded label in the color of [status], with its icon: what a section's header says about
 * the section. [text] replaces the status's own word ("On", "2 left").
 */
@Composable
fun StatusPill(status: Status, text: String? = null, modifier: Modifier = Modifier) {
    val (icon, tint) = status.look()
    Surface(
        color = tint.copy(alpha = 0.12f),
        contentColor = tint,
        shape = RoundedCornerShape(50),
        modifier = modifier,
    ) {
        Row(Modifier.padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text ?: status.label(), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * A titled group of a page: the title, optionally something at its end (a [StatusPill], a menu), and
 * a card with the content. Pages stack sections 24 dp apart; the title sits 8 dp above its card, so
 * each title reads as belonging to the card under it.
 */
@Composable
fun Section(
    title: String?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null || trailing != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                trailing?.invoke()
            }
        }
        SectionCard(content = content)
    }
}

/** The space between a page's sections. */
val SectionSpacing = 24.dp

/**
 * One row of a card: an icon, a title and an optional explanation, and something at the end. With
 * [onClick] the whole row is the button. [tint] colors the icon and title (violet for administration,
 * red for what destroys).
 */
@Composable
fun ActionRow(
    icon: ImageVector?,
    title: String,
    detail: String? = null,
    tint: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = if (tint == Color.Unspecified) LocalContentColor.current else tint) } },
        headlineContent = { Text(title, color = tint) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = trailing,
        modifier = if (onClick != null) Modifier.clickable(role = Role.Button, enabled = enabled, onClick = onClick) else Modifier,
    )
}

/**
 * A strong notice at the top of a page, for the one thing that has to come first (the key's backup):
 * a title, an explanation and the action that fixes it.
 */
@Composable
fun Banner(title: String, body: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(action) }
            }
        }
    }
}

/** A page's column: scrolls, 16 dp from the edges, at most 640 dp wide and centered on wide screens. */
@Composable
fun PageColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing),
            content = content,
        )
    }
}

/**
 * One line of a status list: an icon and color for [status], a title and an explanation. When
 * [onClick] is given, the line opens what fixes or explains it.
 */
@Composable
fun StatusLine(status: Status, title: String, detail: String?, onClick: (() -> Unit)? = null) {
    val (icon, tint) = status.look()
    val label = status.label()
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
        leadingContent = { Icon(icon, contentDescription = label, tint = tint) },
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        modifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
    )
}

/**
 * Keeps whatever is on screen out of screenshots, screen recordings and the recent-apps preview while
 * this is composed. Every screen that shows a private key uses it.
 */
@Composable
fun SecureScreen() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
