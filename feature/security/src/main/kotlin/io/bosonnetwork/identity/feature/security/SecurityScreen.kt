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

package io.bosonnetwork.identity.feature.security

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.identity.core.designsystem.component.ActionRow
import io.bosonnetwork.identity.core.designsystem.component.PageColumn
import io.bosonnetwork.identity.core.designsystem.component.RecoveryCodesDialog
import io.bosonnetwork.identity.core.designsystem.component.Section
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.designsystem.component.Status
import io.bosonnetwork.identity.core.designsystem.component.StatusLine
import io.bosonnetwork.identity.core.designsystem.component.StatusPill
import io.bosonnetwork.identity.core.designsystem.component.look
import io.bosonnetwork.identity.core.designsystem.component.shortId
import io.bosonnetwork.identity.core.designsystem.theme.brand
import io.bosonnetwork.identity.core.model.IdentityInfo
import kotlinx.coroutines.launch

/**
 * The security of the identity in use: the account passphrase and the ways to recover it, the
 * accounts and passkeys that sign in as the identity, the security log, the key's backup and this
 * phone's own key, and the app lock. [onBackup] opens the key's backup; [onJoinNode] joining a node.
 * [navigationIcon] goes at the start of the top bar; the app puts its identity switcher there.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SecurityScreen(
    onBackup: (userId: String) -> Unit,
    onJoinNode: (userId: String) -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    viewModel: SecurityViewModel = hiltViewModel(),
    passphraseViewModel: PassphraseViewModel = hiltViewModel(),
    forgotViewModel: ForgotPassphraseViewModel = hiltViewModel(),
    linkViewModel: LinkAccountViewModel = hiltViewModel(),
    backupViewModel: PasskeyBackupViewModel = hiltViewModel(),
) {
    val passkeyBackup by backupViewModel.state.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val passphrase by passphraseViewModel.state.collectAsStateWithLifecycle()
    val forgot by forgotViewModel.state.collectAsStateWithLifecycle()
    val link by linkViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val identity = state.identity

    // After a change made here, the account's security is asked for again.
    LaunchedEffect(passphrase.changes, link.changes) {
        if (passphrase.changes > 0 || link.changes > 0) viewModel.refresh()
    }
    LaunchedEffect(forgot.step) { if (forgot.step == ForgotStep.DONE) viewModel.refresh() }
    LaunchedEffect(passphrase.notice) {
        passphrase.notice?.let {
            passphraseViewModel.noticeShown()
            snackbar.showSnackbar(it)
        }
    }
    LaunchedEffect(forgot.openUrl) {
        forgot.openUrl?.let {
            forgotViewModel.urlOpened()
            openInBrowserTab(context, it)
        }
    }
    LaunchedEffect(link.openUrl) {
        link.openUrl?.let {
            linkViewModel.urlOpened()
            openInBrowserTab(context, it)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sec_title)) },
                navigationIcon = navigationIcon,
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.sec_refresh))
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
                val ready = (state.overview as? OverviewState.Ready)?.overview
                val logRequester = remember { BringIntoViewRequester() }
                val scope = rememberCoroutineScope()
                Summary(
                    issues = state.issues,
                    known = ready != null || identity.homeNode == null,
                    onIssue = { issue ->
                        when (issue) {
                            Issue.BACKUP -> onBackup(identity.userId)
                            Issue.NO_PASSPHRASE -> passphraseViewModel.start(PassphraseAction.SET)
                            Issue.NO_RECOVERY, Issue.NO_RECOVERY_CODES -> passphraseViewModel.start(PassphraseAction.NEW_CODES)
                            Issue.NEW_LOG_EVENTS -> scope.launch { logRequester.bringIntoView() }
                        }
                    },
                )
                KeyBackupSection(
                    identity,
                    passkeyBackup,
                    onBackup = { onBackup(identity.userId) },
                    onEnablePasskey = backupViewModel::startEnable,
                    onDisablePasskey = backupViewModel::disable,
                )
                when (val o = state.overview) {
                    OverviewState.None -> Section(stringResource(R.string.sec_account_title)) {
                        Note(stringResource(R.string.sec_no_node), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
                        Button(onClick = { onJoinNode(identity.userId) }, modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.sec_join))
                        }
                    }
                    OverviewState.Loading -> Section(stringResource(R.string.sec_account_title)) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    OverviewState.Locked -> Section(stringResource(R.string.sec_account_title)) {
                        StatusLine(Status.ATTENTION, stringResource(R.string.sec_locked), stringResource(R.string.sec_locked_detail), viewModel::refresh)
                    }
                    is OverviewState.Failed -> Section(stringResource(R.string.sec_account_title)) {
                        StatusLine(Status.PROBLEM, stringResource(R.string.sec_failed), o.message, viewModel::refresh)
                    }
                    is OverviewState.Ready -> {
                        PassphraseSection(
                            o.overview,
                            onSet = { passphraseViewModel.start(PassphraseAction.SET) },
                            onChange = { passphraseViewModel.start(PassphraseAction.CHANGE) },
                            onRemove = { passphraseViewModel.start(PassphraseAction.REMOVE) },
                            onForgot = forgotViewModel::open,
                        )
                        if (o.overview.passphraseProtected) {
                            RecoverySection(o.overview.recovery, onNewCodes = { passphraseViewModel.start(PassphraseAction.NEW_CODES) })
                        }
                        SignInSection(
                            o.overview,
                            onLink = { linkViewModel.start(o.overview.passphraseProtected) },
                            onUnlink = viewModel::startUnlink,
                        )
                    }
                }
                PhoneKeySection(identity, onRenew = viewModel::startRenew)
                (state.overview as? OverviewState.Ready)?.overview?.log?.let { log ->
                    SecurityLogSection(
                        log,
                        newEvents = newLogEvents(identity, log).size,
                        reviewedAt = identity.logReviewedAt,
                        onReviewed = viewModel::markLogReviewed,
                        modifier = Modifier.bringIntoViewRequester(logRequester),
                    )
                }
            }
        }
    }

    // ---- Dialogs -------------------------------------------------------------------------------

    if (passkeyBackup.confirming) {
        AlertDialog(
            onDismissRequest = backupViewModel::cancel,
            containerColor = MaterialTheme.brand.card,
            title = { Text(stringResource(R.string.sec_pkb_confirm_title)) },
            text = { Text(stringResource(R.string.sec_pkb_confirm_body)) },
            confirmButton = { TextButton(onClick = backupViewModel::enable) { Text(stringResource(R.string.sec_pkb_confirm_action)) } },
            dismissButton = { TextButton(onClick = backupViewModel::cancel) { Text(stringResource(R.string.sec_cancel)) } },
        )
    }

    passphrase.form?.let { form ->
        PassphraseDialog(
            form,
            onCurrentChange = passphraseViewModel::onCurrentChange,
            onNewChange = passphraseViewModel::onNewChange,
            onConfirmChange = passphraseViewModel::onConfirmChange,
            onSubmit = passphraseViewModel::submit,
            onCancel = passphraseViewModel::cancel,
        )
    }
    passphrase.codes?.let { codes ->
        RecoveryCodesDialog(codes, userId = identity?.userId.orEmpty(), onDone = passphraseViewModel::codesSaved)
    }
    if (forgot.step != ForgotStep.CLOSED) {
        ForgotPassphraseDialog(forgot, forgotViewModel)
    }
    if (link.step != LinkStep.CLOSED) {
        LinkAccountDialog(link, identity?.homeNode?.hostName().orEmpty(), linkViewModel)
    }
    state.unlink?.let { unlink ->
        UnlinkDialog(
            unlink,
            onPassphraseChange = viewModel::onUnlinkPassphraseChange,
            onUnlink = viewModel::unlink,
            onCancel = viewModel::cancelUnlink,
        )
    }
    state.renewal?.let { renewal ->
        RenewDialog(renewal, onPassphraseChange = viewModel::onRenewPassphraseChange, onRenew = viewModel::renew, onClose = viewModel::closeRenew)
    }
    if ((state.backupNeeded || passphrase.backupNeeded || link.backupNeeded) && identity != null) {
        val dismiss = {
            viewModel.dismissBackupNeeded()
            passphraseViewModel.dismissBackupNeeded()
            linkViewModel.dismissBackupNeeded()
        }
        BackupNeededDialog(
            onBackup = {
                dismiss()
                onBackup(identity.userId)
            },
            onDismiss = dismiss,
        )
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/**
 * The page's answer to "am I protected?": all set, or the things that need the user, each one a
 * button to what fixes it. Until the node has said how the account is protected ([known] false), an
 * all-clear would be premature, so the summary shows only what it already knows to be wrong.
 */
@Composable
private fun Summary(issues: List<Issue>, known: Boolean, onIssue: (Issue) -> Unit) {
    if (issues.isEmpty() && !known) return
    SectionCard {
        if (issues.isEmpty()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = MaterialTheme.brand.success, modifier = Modifier.size(40.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text(stringResource(R.string.sec_summary_ok), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.sec_summary_ok_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@SectionCard
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint) = issues.worst().look()
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
            Text(
                pluralStringResource(R.plurals.sec_summary_issues, issues.size, issues.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        issues.forEach { issue ->
            val (title, detail) = when (issue) {
                Issue.BACKUP -> R.string.sec_issue_backup to R.string.sec_issue_backup_detail
                Issue.NO_RECOVERY -> R.string.sec_recovery_none to R.string.sec_recovery_none_detail
                Issue.NO_PASSPHRASE -> R.string.sec_passphrase_off to R.string.sec_passphrase_off_detail
                Issue.NO_RECOVERY_CODES -> R.string.sec_recovery_no_codes to R.string.sec_issue_codes_detail
                Issue.NEW_LOG_EVENTS -> R.string.sec_issue_log to R.string.sec_issue_log_detail
            }
            StatusLine(issue.status, stringResource(title), stringResource(detail), onClick = { onIssue(issue) })
        }
    }
}

/** The key's backup, and the passkey copy that can restore it on a new phone. */
@Composable
private fun KeyBackupSection(
    identity: IdentityInfo,
    passkey: PasskeyBackupUiState,
    onBackup: () -> Unit,
    onEnablePasskey: () -> Unit,
    onDisablePasskey: () -> Unit,
) {
    Section(
        stringResource(R.string.sec_key_title),
        trailing = {
            if (identity.backupVerified) {
                StatusPill(Status.OK, stringResource(R.string.sec_pill_backed_up))
            } else {
                StatusPill(Status.ATTENTION, stringResource(R.string.sec_pill_not_backed_up))
            }
        },
    ) {
        ActionRow(Icons.Filled.Save, stringResource(R.string.sec_backup_key), stringResource(R.string.sec_backup_key_detail), onClick = onBackup)
        val detail = when (passkey.status) {
            BackupStatus.CHECKING -> stringResource(R.string.sec_pkb_checking)
            BackupStatus.ON -> stringResource(R.string.sec_pkb_on)
            BackupStatus.OFF -> stringResource(R.string.sec_pkb_off)
            BackupStatus.UNAVAILABLE -> stringResource(R.string.sec_pkb_unavailable)
        }
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
            leadingContent = { Icon(Icons.Filled.Key, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.sec_pkb_title)) },
            supportingContent = {
                Text(passkey.error ?: detail, color = if (passkey.error != null) MaterialTheme.colorScheme.error else Color.Unspecified)
            },
            trailingContent = {
                when {
                    passkey.busy || passkey.status == BackupStatus.CHECKING ->
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    passkey.status == BackupStatus.ON -> TextButton(onClick = onDisablePasskey) { Text(stringResource(R.string.sec_pkb_disable)) }
                    passkey.status == BackupStatus.OFF -> TextButton(onClick = onEnablePasskey) { Text(stringResource(R.string.sec_pkb_enable)) }
                    else -> Unit
                }
            },
        )
    }
}

@Composable
private fun PassphraseSection(
    overview: SecurityOverview,
    onSet: () -> Unit,
    onChange: () -> Unit,
    onRemove: () -> Unit,
    onForgot: () -> Unit,
) {
    Section(
        stringResource(R.string.sec_passphrase_title),
        trailing = {
            if (overview.passphraseProtected) {
                StatusPill(Status.OK, stringResource(R.string.sec_pill_on))
            } else {
                StatusPill(Status.ATTENTION, stringResource(R.string.sec_pill_off))
            }
        },
    ) {
        if (overview.passphraseProtected) {
            Note(stringResource(R.string.sec_passphrase_on_detail), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChange) { Text(stringResource(R.string.sec_passphrase_change)) }
                TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.sec_passphrase_remove))
                }
            }
            TextButton(onClick = onForgot, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                Text(stringResource(R.string.sec_forgot_action))
            }
        } else {
            Note(stringResource(R.string.sec_passphrase_off_detail), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
            Button(onClick = onSet, modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.sec_passphrase_set))
            }
        }
    }
}

@Composable
private fun RecoverySection(recovery: RecoveryView?, onNewCodes: () -> Unit) {
    Section(
        stringResource(R.string.sec_recovery_title),
        trailing = {
            when {
                recovery == null -> Unit
                !recovery.any -> StatusPill(Status.PROBLEM, stringResource(R.string.sec_pill_none))
                recovery.recoveryCodes == 0 -> StatusPill(Status.ATTENTION, stringResource(R.string.sec_pill_no_codes))
                else -> StatusPill(Status.OK, pluralStringResource(R.plurals.sec_pill_codes, recovery.recoveryCodes, recovery.recoveryCodes))
            }
        },
    ) {
        val text = when {
            // Older nodes don't say; making codes still works.
            recovery == null -> stringResource(R.string.sec_recovery_unknown)
            !recovery.any -> stringResource(R.string.sec_recovery_none_detail)
            recovery.recoveryCodes == 0 -> stringResource(R.string.sec_recovery_others, recovery.accounts, recovery.passkeys)
            recovery.accounts + recovery.passkeys > 0 -> stringResource(R.string.sec_recovery_others, recovery.accounts, recovery.passkeys)
            else -> stringResource(R.string.sec_recovery_codes_detail)
        }
        Note(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        OutlinedButton(onClick = onNewCodes, modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.sec_recovery_new_codes))
        }
    }
}

@Composable
private fun SignInSection(overview: SecurityOverview, onLink: () -> Unit, onUnlink: (LinkedView) -> Unit) {
    val count = (overview.linked?.size ?: 0) + overview.passkeys.size
    Section(
        stringResource(R.string.sec_signin_title),
        trailing = {
            if (count > 0) {
                StatusPill(Status.OK, pluralStringResource(R.plurals.sec_pill_methods, count, count))
            } else {
                StatusPill(Status.OFF, stringResource(R.string.sec_pill_none))
            }
        },
    ) {
        Text(
            stringResource(R.string.sec_linked_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        val linked = overview.linked
        when {
            linked == null -> Note(stringResource(R.string.sec_linked_unknown))
            linked.isEmpty() -> Note(stringResource(R.string.sec_linked_none))
            else -> linked.forEach { account ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null) },
                    headlineContent = { Text(account.provider ?: stringResource(R.string.sec_linked_unknown_provider)) },
                    supportingContent = listOfNotNull(account.name, account.email).joinToString(" - ").takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                    trailingContent = {
                        TextButton(onClick = { onUnlink(account) }) { Text(stringResource(R.string.sec_unlink)) }
                    },
                )
            }
        }
        OutlinedButton(onClick = onLink, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)) {
            Text(stringResource(R.string.sec_link_action))
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Text(
            stringResource(R.string.sec_passkeys_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        if (overview.passkeys.isEmpty()) {
            Note(stringResource(R.string.sec_passkeys_none))
        } else {
            overview.passkeys.forEach { passkey ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
                    leadingContent = { Icon(Icons.Filled.Key, contentDescription = null) },
                    headlineContent = { Text(passkey.name.ifBlank { stringResource(R.string.sec_unnamed) }) },
                    trailingContent = if (passkey.recoveryMethod) {
                        { RecoveryBadge() }
                    } else {
                        null
                    },
                )
            }
        }
        Note(stringResource(R.string.sec_passkeys_hint), Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
    }
}

@Composable
private fun RecoveryBadge() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            stringResource(R.string.sec_badge_recovery),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * The security log, newest first. Events newer than what the user reviewed on this phone ([reviewedAt])
 * are marked new, and counted in the header; "Mark as reviewed" ([onReviewed]) makes them old. The log
 * itself can't be cleared: it is the account's audit trail, kept by the node.
 */
@Composable
private fun SecurityLogSection(
    log: List<LogEntry>,
    newEvents: Int,
    reviewedAt: Long?,
    onReviewed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var all by rememberSaveable { mutableStateOf(false) }
    Section(
        stringResource(R.string.sec_log_title),
        modifier = modifier,
        trailing = {
            if (newEvents > 0) StatusPill(Status.ATTENTION, pluralStringResource(R.plurals.sec_pill_log_new, newEvents, newEvents))
        },
    ) {
        if (log.isEmpty()) {
            Note(stringResource(R.string.sec_log_empty), Modifier.padding(16.dp))
            return@Section
        }
        // The new ones always show, however many.
        val shown = if (all) log else log.take(maxOf(LOG_SHOWN, newEvents))
        shown.forEach { entry ->
            val new = reviewedAt != null && entry.at > reviewedAt
            ListItem(
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
                headlineContent = { Text(entry.sentence(), fontWeight = if (new) FontWeight.SemiBold else null) },
                supportingContent = { Text(entry.whenAndWhere()) },
                trailingContent = if (new) {
                    { Text(stringResource(R.string.sec_log_new), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                } else {
                    null
                },
            )
        }
        Row(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (log.size > shown.size || all) {
                TextButton(onClick = { all = !all }) {
                    Text(stringResource(if (all) R.string.sec_log_fewer else R.string.sec_log_all))
                }
            }
            Spacer(Modifier.weight(1f))
            if (newEvents > 0) {
                TextButton(onClick = onReviewed) { Text(stringResource(R.string.sec_log_mark_reviewed)) }
            }
        }
    }
}

private const val LOG_SHOWN = 10

/** The entry as a sentence, in the user's language. */
@Composable
internal fun LogEntry.sentence(): String {
    val name = subject ?: stringResource(R.string.sec_unnamed)
    return when (kind) {
        LogKind.PASSPHRASE_SET -> stringResource(R.string.sec_log_set)
        LogKind.PASSPHRASE_CHANGED -> stringResource(R.string.sec_log_changed)
        LogKind.PASSPHRASE_REMOVED -> stringResource(R.string.sec_log_removed)
        LogKind.PASSPHRASE_RESET -> when (resetMethod) {
            ResetMethod.RECOVERY_CODE -> stringResource(R.string.sec_log_reset_code)
            ResetMethod.ACCOUNT -> subject?.let { stringResource(R.string.sec_log_reset_account, it) }
                ?: stringResource(R.string.sec_log_reset_linked)
            ResetMethod.PASSKEY -> stringResource(R.string.sec_log_reset_passkey)
            else -> stringResource(R.string.sec_log_reset)
        }
        LogKind.RECOVERY_CODES -> stringResource(R.string.sec_log_codes)
        LogKind.DEVICE_ADDED -> stringResource(if (passkey) R.string.sec_log_passkey_added else R.string.sec_log_device_added, name)
        LogKind.DEVICE_REMOVED -> stringResource(if (passkey) R.string.sec_log_passkey_removed else R.string.sec_log_device_removed, name)
        LogKind.ACCOUNT_LINKED -> stringResource(R.string.sec_log_linked, subject ?: stringResource(R.string.sec_linked_unknown_provider))
        LogKind.ACCOUNT_UNLINKED -> stringResource(R.string.sec_log_unlinked, subject ?: stringResource(R.string.sec_linked_unknown_provider))
        LogKind.ADMIN_ENROLLED -> if (subject == null || subject == "root") stringResource(R.string.sec_log_admin_enrolled_root)
            else stringResource(R.string.sec_log_admin_enrolled_by, shortId(subject))
        LogKind.SIGNED_IN -> when (subject) {
            "portal" -> stringResource(R.string.sec_log_signed_in_portal)
            "admin" -> stringResource(R.string.sec_log_signed_in_admin)
            else -> stringResource(R.string.sec_log_signed_in)
        }
        LogKind.OTHER -> name
    }
}

/** "5 minutes ago, from 203.0.113.7". */
@Composable
private fun LogEntry.whenAndWhere(): String {
    val now = System.currentTimeMillis()
    // The node's clock may be ahead of this phone's: never say "in 2 minutes".
    val time = if (at > 0) {
        DateUtils.getRelativeTimeSpanString(minOf(at, now), now, DateUtils.MINUTE_IN_MILLIS).toString()
    } else {
        stringResource(R.string.sec_log_unknown_time)
    }
    return address?.let { stringResource(R.string.sec_log_when_from, time, it) } ?: time
}

/** This phone's own protection: the screen lock that guards the key, and the phone's device key. */
@Composable
private fun PhoneKeySection(identity: IdentityInfo, onRenew: () -> Unit) {
    Section(
        stringResource(R.string.sec_phone_title),
        trailing = { StatusPill(Status.OK, stringResource(R.string.sec_pill_locked)) },
    ) {
        ActionRow(Icons.Filled.Lock, stringResource(R.string.sec_app_lock), stringResource(R.string.sec_app_lock_detail))
        if (identity.homeNode != null && identity.deviceId != null) {
            ActionRow(Icons.Filled.Autorenew, stringResource(R.string.sec_renew), stringResource(R.string.sec_renew_detail), onClick = onRenew)
        }
    }
}

/**
 * Opens [url] in a browser tab over the app (Custom Tabs), or in the browser when no browser supports
 * tabs. The tab shares the browser's sign-ins, so the user signs in at the provider as usual.
 */
internal fun openInBrowserTab(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // No browser at all; the dialog stays, and the user can cancel.
        }
    }
}
