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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.IdentityPicture
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.designsystem.theme.brand

/**
 * Choosing the identity an app's [request] is answered for: who asked (the app's name and package),
 * what it gets, and the identities on this phone, those without a home node shown but not choosable.
 * [onConfirm] shares the home node, or goes on to the approval; [onClose] answers nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRequestScreen(
    request: AppRequest,
    state: AppRequestState,
    avatars: Map<String, ByteArray>,
    onSelect: (userId: String) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    val sharing = request is AppRequest.HomeNode
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (sharing) R.string.app_request_share_title else R.string.app_request_approve_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.app_request_close)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                if (state.loading) {
                    Spacer(Modifier.height(48.dp))
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    return@Column
                }

                // Who asks, and what for.
                SectionCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(request.requesterLabel, style = MaterialTheme.typography.titleMedium)
                        Text(
                            request.requesterPackage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(
                                if (sharing) R.string.app_request_share_body else R.string.app_request_approve_body,
                                request.requesterLabel,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                when (state.unanswerable) {
                    AppRequests.ERROR_NO_IDENTITY -> {
                        Text(stringResource(R.string.app_request_no_identity), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_request_close)) }
                        return@Column
                    }
                    AppRequests.ERROR_NO_HOME_NODE ->
                        Text(stringResource(R.string.app_request_no_home_node), style = MaterialTheme.typography.bodyMedium)
                    else ->
                        Text(
                            stringResource(if (sharing) R.string.app_request_choose_share else R.string.app_request_choose_approve),
                            style = MaterialTheme.typography.titleSmall,
                        )
                }
                Spacer(Modifier.height(8.dp))

                state.identities.forEach { identity ->
                    val node = identity.homeNode
                    val selected = identity.userId == state.selected
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
                        leadingContent = { IdentityPicture(identity.userId, avatars[identity.userId], size = 40.dp) },
                        headlineContent = { Text(identity.name ?: stringResource(R.string.app_identity_unnamed)) },
                        supportingContent = {
                            Column {
                                IdText(identity.userId, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    node?.let { Uri.parse(it.url).host ?: it.url } ?: stringResource(R.string.app_identity_no_node),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        trailingContent = { RadioButton(selected = selected, onClick = null, enabled = node != null) },
                        modifier = Modifier
                            .alpha(if (node != null) 1f else 0.5f)
                            .selectable(selected = selected, enabled = node != null && !state.working, role = Role.RadioButton) {
                                onSelect(identity.userId)
                            },
                    )
                }

                state.error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onConfirm,
                    enabled = state.selected != null && !state.working,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.working) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(if (sharing) R.string.app_request_share_action else R.string.app_request_continue))
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_request_cancel)) }
            }
        }
    }
}
