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

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * One destination of the tab bar: its [route] (also the key its saved state is kept under), [label]
 * and [icon], and the screen it shows. The screen gets the identity button to put at the start of
 * its top bar. An [adminOnly] tab is shown only to an identity that is an admin at a node.
 */
class HomeTab(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
    val adminOnly: Boolean = false,
    val content: @Composable (identityButton: @Composable () -> Unit) -> Unit,
)

/**
 * The tabs: a bottom navigation bar on phones, a navigation rail from 600 dp wide (tablets,
 * unfolded foldables, landscape). Each tab keeps its scroll position and inputs while another is
 * shown. Back from any other tab returns to the first before it leaves the app.
 */
@Composable
fun HomeTabs(tabs: List<HomeTab>, showAdmin: Boolean, identityButton: @Composable () -> Unit) {
    val visible = tabs.filter { showAdmin || !it.adminOnly }
    var selected by rememberSaveable { mutableStateOf(visible.first().route) }
    val current = visible.firstOrNull { it.route == selected } ?: visible.first()
    val saved = rememberSaveableStateHolder()
    BackHandler(enabled = current != visible.first()) { selected = visible.first().route }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = maxWidth >= 600.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            // The screens inside handle the system bars themselves; this only makes room for the bar.
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (!rail) {
                    NavigationBar {
                        visible.forEach { tab ->
                            NavigationBarItem(
                                selected = tab == current,
                                onClick = { selected = tab.route },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (rail) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                        visible.forEach { tab ->
                            NavigationRailItem(
                                selected = tab == current,
                                onClick = { selected = tab.route },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                }
                // Beside the rail, the screen no longer touches the start edge, nor its system bar.
                val besideRail = if (rail) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else Modifier
                Box(Modifier.weight(1f).fillMaxHeight().then(besideRail)) {
                    saved.SaveableStateProvider(current.route) { current.content(identityButton) }
                }
            }
        }
    }
}
