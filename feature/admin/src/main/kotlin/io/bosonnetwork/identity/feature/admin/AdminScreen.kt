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

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.model.NodeAddress
import java.util.Locale
import kotlinx.coroutines.delay

/** The sections of Admin, in their order. */
enum class AdminTab(@StringRes val label: Int) {
    OVERVIEW(R.string.adm_tab_overview),
    USERS(R.string.adm_tab_users),
    PLANS(R.string.adm_tab_plans),
    FEDERATION(R.string.adm_tab_federation),
    BLACKLIST(R.string.adm_tab_blacklist),
}

/**
 * Admin: administering the home node, in the brand's violet so it is never mistaken for the user's
 * own screens. Without an admin session it offers to open one (one prompt, never on its own); with
 * one, a violet bar says how long it has left, beside "End now", above the sections.
 * [navigationIcon] goes at the start of the top bar; the app puts its identity switcher there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    navigationIcon: @Composable () -> Unit = {},
    viewModel: AdminViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AdminTab.OVERVIEW) }
    // Bumped by the refresh button; the section shown asks its node again.
    var refreshes by remember { mutableIntStateOf(0) }
    val node = state.identity?.homeNode
    val session = state.session

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.adm_title))
                        node?.let {
                            Text(it.host(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                },
                navigationIcon = navigationIcon,
                actions = {
                    if (session != null) {
                        IconButton(onClick = { refreshes++ }) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.adm_refresh))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (node == null || session == null) {
                Unlock(node, state, onUnlock = viewModel::start)
                return@Column
            }
            AdminModeBar(session.expiresAt, onEnd = viewModel::end, onExpired = viewModel::expired)
            ScrollableTabRow(
                selectedTabIndex = tab.ordinal,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.tertiary,
                edgePadding = 8.dp,
                indicator = { positions ->
                    if (tab.ordinal < positions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(positions[tab.ordinal]),
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                },
            ) {
                AdminTab.entries.forEach { entry ->
                    Tab(
                        selected = entry == tab,
                        onClick = { tab = entry },
                        text = { Text(stringResource(entry.label)) },
                        selectedContentColor = MaterialTheme.colorScheme.tertiary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (tab) {
                AdminTab.OVERVIEW -> OverviewTab(refreshes)
                AdminTab.USERS -> UsersTab(refreshes)
                AdminTab.PLANS -> PlansTab(refreshes)
                AdminTab.FEDERATION -> FederationTab(refreshes)
                AdminTab.BLACKLIST -> BlacklistTab(refreshes)
            }
        }
    }
}

/** Asks the section's view model again when the refresh button was pressed (not on first showing). */
@Composable
internal fun RefreshOn(refreshes: Int, refresh: () -> Unit) {
    val current by rememberUpdatedState(refresh)
    var seen by rememberSaveable { mutableIntStateOf(refreshes) }
    LaunchedEffect(refreshes) {
        if (refreshes != seen) {
            seen = refreshes
            current()
        }
    }
}

/** No admin session: what admin mode is, and the one button that opens it. */
@Composable
private fun Unlock(node: NodeAddress?, state: AdminUiState, onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionCard(Modifier.widthIn(max = 640.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.AdminPanelSettings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(48.dp),
                )
                Text(stringResource(R.string.adm_unlock_title), style = MaterialTheme.typography.titleLarge)
                if (node == null) {
                    Text(stringResource(R.string.adm_no_node), textAlign = TextAlign.Center)
                    return@Column
                }
                Text(stringResource(R.string.adm_unlock_body, node.host()), textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                if (state.starting) {
                    Busy()
                } else {
                    Button(
                        onClick = onUnlock,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                    ) { Text(stringResource(R.string.adm_unlock)) }
                }
                ErrorText(state.error)
            }
        }
    }
}

/**
 * "Admin mode, 9:42 left", and "End now". When the time is up, [onExpired] runs: the session also has
 * its own timer, but that one may run late after the phone slept, and this one reads the clock.
 */
@Composable
private fun AdminModeBar(expiresAt: Long, onEnd: () -> Unit, onExpired: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val expired by rememberUpdatedState(onExpired)
    LaunchedEffect(expiresAt) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= expiresAt) {
                expired()
                break
            }
            // Until the shown second changes.
            delay(((expiresAt - now) % 1_000).let { if (it == 0L) 1_000L else it } + 1)
        }
    }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.adm_mode_left, timeLeft(expiresAt - now)),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            TextButton(onClick = onEnd, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)) {
                Text(stringResource(R.string.adm_end_now))
            }
        }
    }
}

/** Minutes and seconds, as "9:42"; rounded up, so it shows 0:00 only when the time is up. */
fun timeLeft(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + 999) / 1_000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
