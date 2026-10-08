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

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.Id
import io.bosonnetwork.identity.core.designsystem.component.Banner
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.Identicon
import io.bosonnetwork.identity.core.designsystem.component.IdentityPicture
import io.bosonnetwork.identity.core.designsystem.component.PageColumn
import io.bosonnetwork.identity.core.designsystem.component.Section
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.designsystem.component.Status
import io.bosonnetwork.identity.core.designsystem.component.StatusPill
import io.bosonnetwork.identity.core.designsystem.component.copyToClipboard
import io.bosonnetwork.identity.core.designsystem.theme.brand
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.qr.rememberQrBitmap
import kotlinx.coroutines.delay

/**
 * The Identity tab: who the identity is and where it lives. The profile comes first, with its id
 * (tap for the QR code) and the public profile card; then the home node, with its status, the plan
 * and, for the accounts marked as its administrators, the Dashboard: the one way into administration.
 * The node's root user is not one of them here: it is the node's safeguard, kept off phones and remote
 * access, and administers through the node itself. [onOpenAdmin] opens
 * it. [onScan] opens the app's scanner, for a web page's sign-in code or a new device's pairing code.
 * [navigationIcon] goes at the start of the top bar; the app puts its identity switcher there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityScreen(
    onBackup: (userId: String) -> Unit,
    onJoinNode: (userId: String) -> Unit,
    onOpenAdmin: () -> Unit,
    onScan: (userId: String) -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    viewModel: IdentityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showId by rememberSaveable { mutableStateOf(false) }
    var showNode by rememberSaveable { mutableStateOf(false) }
    val identity = state.identity

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.id_title)) },
                navigationIcon = navigationIcon,
                actions = {
                    // Both codes it reads are acted on at the home node, so it waits for one.
                    if (identity?.homeNode != null) {
                        IconButton(onClick = { onScan(identity.userId) }) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.id_scan))
                        }
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.id_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (identity == null) return@Scaffold
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            PageColumn {
                if (!identity.backupVerified) {
                    Banner(
                        title = stringResource(R.string.id_backup_title),
                        body = stringResource(R.string.id_backup_body),
                        action = stringResource(R.string.id_backup_action),
                        onAction = { onBackup(identity.userId) },
                    )
                }
                ProfileCard(
                    identity = identity,
                    state = state,
                    onShowId = { showId = true },
                    onEdit = viewModel::startEdit,
                    onCard = viewModel::requestCard,
                    onRetryCard = viewModel::refresh,
                )
                HomeNodeSection(
                    identity = identity,
                    state = state,
                    // The node's code pins it by its id: until the id is known, there is no code to show.
                    onShowQr = if (((state.node as? NodeState.Ready)?.node?.nodeId ?: identity.homeNode?.nodeId) != null) {
                        { showNode = true }
                    } else {
                        null
                    },
                    onJoin = { onJoinNode(identity.userId) },
                    onUnlock = viewModel::refresh,
                    onOpenAdmin = onOpenAdmin,
                )
            }
        }
    }

    if (showId && identity != null) {
        QrSheet(
            title = stringResource(R.string.id_my_id_title),
            // The id as a W3C DID: what Boson apps read, and what any DID tool recognizes.
            qrText = Id.of(identity.userId).toDIDString(),
            qrDescription = stringResource(R.string.id_my_id_qr),
            id = identity.userId,
            body = stringResource(R.string.id_my_id_body),
            copyLabel = stringResource(R.string.id_my_id_title),
            copyText = identity.userId,
            onDismiss = { showId = false },
        )
    }
    val node = identity?.homeNode
    val nodeId = (state.node as? NodeState.Ready)?.node?.nodeId ?: node?.nodeId
    if (showNode && node != null && nodeId != null) {
        val view = (state.node as? NodeState.Ready)?.node
        QrSheet(
            title = view?.name ?: node.host(),
            qrText = nodeQrPayload(node, nodeId),
            qrDescription = stringResource(R.string.id_node_qr_desc),
            id = nodeId,
            subtitle = node.url,
            body = stringResource(R.string.id_node_qr_body),
            copyLabel = stringResource(R.string.id_node_title),
            copyText = node.url,
            onDismiss = { showNode = false },
        )
    }
    state.edit?.let { edit ->
        EditProfileDialog(
            edit = edit,
            onNameChange = viewModel::onNameChange,
            onBioChange = viewModel::onBioChange,
            onEmailChange = viewModel::onEmailChange,
            onPassphraseChange = viewModel::onPassphraseChange,
            onSave = viewModel::save,
            onCancel = viewModel::cancelEdit,
        )
    }
    state.card.confirm?.let { confirm ->
        CardConfirmDialog(confirm, onConfirm = viewModel::confirmCard, onCancel = viewModel::cancelCard)
    }
}

// ---- Profile -----------------------------------------------------------------------------------

/**
 * Who the identity is: its identicon, name and id, what it says about itself, and whether the profile
 * is published as a public card. Editing (top-right) needs the home node, which keeps the profile.
 */
@Composable
private fun ProfileCard(
    identity: IdentityInfo,
    state: IdentityUiState,
    onShowId: () -> Unit,
    onEdit: () -> Unit,
    onCard: (Boolean) -> Unit,
    onRetryCard: () -> Unit,
) {
    val ready = state.node as? NodeState.Ready
    val profile = ready?.profile
    val name = profile?.name ?: identity.name
    SectionCard {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            IdentityPicture(
                identity.userId,
                state.avatar,
                Modifier.padding(top = 4.dp),
                size = 64.dp,
                contentDescription = stringResource(R.string.id_avatar),
            )
            Column(Modifier.padding(start = 16.dp, top = 6.dp).weight(1f)) {
                Text(
                    name ?: stringResource(R.string.id_unnamed),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                IdChip(identity.userId, onClick = onShowId)
            }
            if (profile != null) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.id_edit))
                }
            } else {
                Spacer(Modifier.size(12.dp))
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                identity.homeNode == null -> Hint(stringResource(R.string.id_profile_no_node))
                profile == null -> Unit
                profile.bio == null && profile.email == null && profile.name == null -> Hint(stringResource(R.string.id_profile_empty))
                else -> {
                    profile.bio?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    profile.email?.let { email ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Email,
                                contentDescription = stringResource(R.string.id_email),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                email,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }
        if (identity.homeNode != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            PublicCardRow(state.card, profileReady = profile != null, onCard = onCard, onRetry = onRetryCard)
        }
    }
}

/** The abbreviated id with a QR glyph: the button that shows the id's QR code. */
@Composable
private fun IdChip(id: String, onClick: () -> Unit) {
    val label = stringResource(R.string.id_show_qr)
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IdText(id, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(
            Icons.Filled.QrCode2,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp).size(18.dp),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The public profile card's switch, with what it means in its current state. */
@Composable
private fun PublicCardRow(card: CardUi, profileReady: Boolean, onCard: (Boolean) -> Unit, onRetry: () -> Unit) {
    val on = card.state is CardState.On
    val usable = profileReady && !card.busy && (card.state == CardState.Off || on)
    val detail = when (val s = card.state) {
        CardState.Unknown, CardState.Checking -> stringResource(R.string.id_card_checking)
        CardState.Unavailable -> stringResource(R.string.id_card_unavailable)
        CardState.Off -> stringResource(R.string.id_card_off)
        is CardState.On -> stringResource(R.string.id_card_on)
        is CardState.Failed -> s.message
    }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
        leadingContent = {
            Icon(Icons.Outlined.Public, contentDescription = null, tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        headlineContent = { Text(stringResource(R.string.id_card_title)) },
        supportingContent = {
            Column {
                Text(detail)
                card.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        trailingContent = {
            when {
                card.busy || card.state == CardState.Checking ->
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                card.state is CardState.Failed ->
                    IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.id_retry)) }
                else -> Switch(checked = on, onCheckedChange = onCard, enabled = usable)
            }
        },
        modifier = if (usable) Modifier.clickable(role = Role.Switch) { onCard(!on) } else Modifier,
    )
}

// ---- Home node ---------------------------------------------------------------------------------

/** Where the identity lives: the home node, its status, the plan, and administration for its operators. */
@Composable
private fun HomeNodeSection(
    identity: IdentityInfo,
    state: IdentityUiState,
    onShowQr: (() -> Unit)?,
    onJoin: () -> Unit,
    onUnlock: () -> Unit,
    onOpenAdmin: () -> Unit,
) {
    val node = identity.homeNode
    Section(
        title = stringResource(R.string.id_node_title),
        trailing = node?.let { { NodeStatusPill(state.node) } },
    ) {
        if (node == null) {
            NoHomeNode(onJoin)
            return@Section
        }
        val ready = state.node as? NodeState.Ready
        val view = ready?.node
        var menu by remember { mutableStateOf(false) }
        val context = LocalContext.current
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            NodeLogo(state, view?.nodeId ?: node.nodeId ?: node.url)
            val showQrLabel = stringResource(R.string.id_show_qr)
            Column(
                Modifier.padding(start = 12.dp).weight(1f).clip(RoundedCornerShape(8.dp))
                    .then(if (onShowQr != null) Modifier.clickable(role = Role.Button, onClickLabel = showQrLabel, onClick = onShowQr) else Modifier)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    view?.name ?: node.host(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (view?.name != null) {
                    Text(
                        node.host(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                (view?.nodeId ?: node.nodeId)?.let { id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IdText(id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(
                            Icons.Filled.QrCode2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp).size(16.dp),
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.id_node_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MaterialTheme.brand.card) {
                    if (onShowQr != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.id_show_qr)) },
                            leadingIcon = { Icon(Icons.Filled.QrCode2, contentDescription = null) },
                            onClick = {
                                menu = false
                                onShowQr()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.id_node_copy)) },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        onClick = {
                            menu = false
                            copyToClipboard(context, context.getString(R.string.id_node_title), node.url)
                        },
                    )
                }
            }
        }
        when (val s = state.node) {
            NodeState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
            NodeState.Locked -> NodeLine(Icons.Filled.Lock, MaterialTheme.brand.warning, stringResource(R.string.id_locked), stringResource(R.string.id_locked_detail), onUnlock)
            is NodeState.Failed -> NodeLine(Icons.Filled.Error, MaterialTheme.colorScheme.error, s.message, stringResource(R.string.id_retry_detail), onUnlock)
            is NodeState.Ready -> Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The plan, shown as what it will become: managing it is not in the app yet.
                FilledTonalButton(onClick = {}) {
                    Icon(Icons.Outlined.WorkspacePremium, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.id_plan, s.profile.plan), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.administers) {
                    // Administration is marked in the brand's violet everywhere it appears.
                    Button(
                        onClick = onOpenAdmin,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                    ) {
                        Icon(Icons.Outlined.Dashboard, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.id_dashboard))
                    }
                }
            }
            NodeState.None -> Unit
        }
    }
}

/** The node's status, in the section's header. */
@Composable
private fun NodeStatusPill(node: NodeState) {
    when (node) {
        NodeState.None -> Unit
        NodeState.Loading -> StatusPill(Status.OFF, stringResource(R.string.id_status_checking))
        NodeState.Locked -> StatusPill(Status.ATTENTION, stringResource(R.string.id_status_locked))
        is NodeState.Failed -> StatusPill(
            Status.PROBLEM,
            stringResource(if (node.unreachable) R.string.id_status_unreachable else R.string.id_status_error),
        )
        is NodeState.Ready -> if (node.node.running) {
            StatusPill(Status.OK, stringResource(R.string.id_status_online))
        } else {
            StatusPill(Status.ATTENTION, stringResource(R.string.id_status_starting))
        }
    }
}

/** The node's logo when it has one this phone could load, its identicon otherwise. */
@Composable
private fun NodeLogo(state: IdentityUiState, fallbackId: String) {
    val logo = state.logo
    if (logo != null) {
        val image = remember(logo) { logo.asImageBitmap() }
        Image(
            image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(4.dp),
        )
    } else {
        Identicon(fallbackId, size = 48.dp)
    }
}

/** A line under the node saying why it shows nothing, and that tapping it tries again. */
@Composable
private fun NodeLine(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, detail: String, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

/** No home node: what one is for, and joining one. */
@Composable
private fun NoHomeNode(onJoin: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.Start) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.id_node_none), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.id_node_none_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onJoin) { Text(stringResource(R.string.id_node_join)) }
    }
}

// ---- QR codes ----------------------------------------------------------------------------------

/**
 * A QR code to show another phone, in a sheet: [qrText] as the code, its title, the abbreviated [id]
 * under it, what it is for, and copying or sharing [copyText].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QrSheet(
    title: String,
    qrText: String,
    qrDescription: String,
    id: String?,
    body: String,
    copyLabel: String,
    copyText: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.brand.card,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(20.dp))
            // Dark modules on white, whatever the theme: scanners expect it.
            Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
                rememberQrBitmap(qrText)?.let {
                    Image(it, contentDescription = qrDescription, modifier = Modifier.padding(12.dp).size(232.dp))
                }
            }
            id?.let {
                Spacer(Modifier.height(16.dp))
                IdText(it, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    copyToClipboard(context, copyLabel, copyText)
                    copied = true
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (copied) R.string.id_copied else R.string.id_copy))
                }
                Button(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, copyText)
                    }
                    context.startActivity(Intent.createChooser(send, title))
                }) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.id_share))
                }
            }
        }
    }
}
