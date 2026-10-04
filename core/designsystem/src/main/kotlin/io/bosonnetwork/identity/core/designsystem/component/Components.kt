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

/** The Boson mark on its dark tile, as on the app icon. */
@Composable
fun BrandTile(modifier: Modifier = Modifier, size: Dp = 72.dp) {
    Image(
        painter = painterResource(R.drawable.ds_boson_tile),
        contentDescription = stringResource(R.string.ds_brand_name),
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

/** How a protection is doing. */
enum class Status { OK, ATTENTION, PROBLEM }

/**
 * One line of a status list: an icon and color for [status], a title and an explanation. When
 * [onClick] is given, the line opens what fixes or explains it.
 */
@Composable
fun StatusLine(status: Status, title: String, detail: String?, onClick: (() -> Unit)? = null) {
    val (icon, tint) = when (status) {
        Status.OK -> Icons.Filled.CheckCircle to MaterialTheme.brand.success
        Status.ATTENTION -> Icons.Filled.Warning to MaterialTheme.brand.warning
        Status.PROBLEM -> Icons.Filled.Error to MaterialTheme.colorScheme.error
    }
    val label = stringResource(
        when (status) {
            Status.OK -> R.string.ds_status_ok
            Status.ATTENTION -> R.string.ds_status_attention
            Status.PROBLEM -> R.string.ds_status_problem
        },
    )
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
