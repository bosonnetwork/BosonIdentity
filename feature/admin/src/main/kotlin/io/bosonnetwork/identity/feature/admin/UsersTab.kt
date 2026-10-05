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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.director.client.Payment
import io.bosonnetwork.director.client.Subscription
import io.bosonnetwork.identity.core.designsystem.component.IdText
import io.bosonnetwork.identity.core.designsystem.component.Identicon
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.designsystem.component.shortId
import io.bosonnetwork.identity.core.designsystem.theme.brand

/** Users: the list, searchable, and a user's detail. */
@Composable
internal fun UsersTab(refreshes: Int, viewModel: UsersViewModel = hiltViewModel()) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    RefreshOn(refreshes) { if (state.selected != null) viewModel.refreshDetail() else viewModel.refresh() }

    if (state.selected != null) {
        BackHandler(onBack = viewModel::close)
        UserDetailContent(state, viewModel)
        return
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            label = { Text(stringResource(R.string.adm_users_search)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LoadedContent(content, onRetry = viewModel::refresh) { pages ->
            val users = viewModel.visible(pages, state)
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                item {
                    Text(
                        pluralStringResource(R.plurals.adm_users_count, pages.total.toInt(), pages.total),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                items(users, key = { it.id }) { user ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                        leadingContent = { Identicon(user.id, size = 40.dp) },
                        headlineContent = { Text(user.name ?: shortId(user.id)) },
                        supportingContent = { Text(user.email ?: shortId(user.id)) },
                        trailingContent = if (user.admin) {
                            { AdminBadge(stringResource(R.string.adm_users_admin)) }
                        } else {
                            null
                        },
                        modifier = Modifier.clickable(role = Role.Button) { viewModel.open(user.id) },
                    )
                }
                if (users.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.adm_users_none),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                if (pages.more) {
                    item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.query.isNotBlank()) {
                                Text(
                                    stringResource(R.string.adm_users_search_partial, pages.users.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (state.loadingMore) {
                                Busy()
                            } else {
                                OutlinedButton(onClick = viewModel::loadMore) {
                                    Text(stringResource(R.string.adm_users_more, pages.users.size, pages.total))
                                }
                            }
                            ErrorText(state.moreError)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserDetailContent(state: UsersUiState, viewModel: UsersViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::close) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.adm_back))
                }
                Text(stringResource(R.string.adm_user_title), style = MaterialTheme.typography.titleLarge)
            }
            LoadedContent(state.detail, onRetry = viewModel::refreshDetail) { detail ->
                UserCard(detail.user, onSetAdmin = { viewModel.ask(UserChange.SetAdmin(it)) })
                DevicesCard(detail.devices, onRemove = { viewModel.ask(UserChange.RemoveDevice(it)) })
                SubscriptionsCard(detail.subscriptions)
                PaymentsCard(
                    detail.payments,
                    onConfirm = { viewModel.ask(UserChange.ConfirmPayment(it)) },
                    onCancel = { viewModel.ask(UserChange.CancelPayment(it)) },
                )
                if (!detail.user.you) {
                    SectionCard {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
                            headlineContent = { Text(stringResource(R.string.adm_user_remove), color = MaterialTheme.colorScheme.error) },
                            supportingContent = { Text(stringResource(R.string.adm_user_remove_detail)) },
                            modifier = Modifier.clickable(role = Role.Button) { viewModel.ask(UserChange.Remove) },
                        )
                    }
                }
                state.change?.let { change -> UserChangeDialog(detail, change, state, viewModel) }
            }
        }
    }
}

@Composable
private fun UserCard(user: UserView, onSetAdmin: (Boolean) -> Unit) {
    val context = LocalContext.current
    SectionCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Identicon(user.id, size = 56.dp)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(user.name ?: stringResource(R.string.adm_user_unnamed), style = MaterialTheme.typography.titleLarge)
                if (user.you) Text(stringResource(R.string.adm_user_you), color = MaterialTheme.colorScheme.tertiary)
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.adm_user_id), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IdText(user.id, style = MaterialTheme.typography.bodyMedium)
        }
        user.email?.let { Field(stringResource(R.string.adm_user_email), it) }
        user.bio?.let { Field(stringResource(R.string.adm_user_bio), it) }
        Field(stringResource(R.string.adm_user_plan), user.plan)
        Field(stringResource(R.string.adm_user_created), context.date(user.createdAt))
        Field(
            stringResource(R.string.adm_user_passphrase),
            stringResource(if (user.passphraseProtected) R.string.adm_yes else R.string.adm_no),
        )
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
            headlineContent = { Text(stringResource(R.string.adm_user_admin)) },
            supportingContent = {
                Text(stringResource(if (user.you) R.string.adm_user_admin_you else R.string.adm_user_admin_detail))
            },
            trailingContent = {
                Switch(
                    checked = user.admin,
                    onCheckedChange = onSetAdmin,
                    enabled = !user.you,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.tertiary,
                        checkedThumbColor = MaterialTheme.colorScheme.onTertiary,
                    ),
                )
            },
        )
    }
}

@Composable
private fun DevicesCard(devices: List<UserDevice>, onRemove: (UserDevice) -> Unit) {
    SectionTitle(stringResource(R.string.adm_user_devices))
    SectionCard {
        if (devices.isEmpty()) Text(stringResource(R.string.adm_user_no_devices), modifier = Modifier.padding(16.dp))
        devices.forEach { device ->
            ListItem(
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
                headlineContent = { Text(device.label()) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            stringResource(if (device.passkey) R.string.adm_device_passkey else R.string.adm_device_app),
                            device.lastSeen.takeIf { it > 0 }?.let { stringResource(R.string.adm_device_seen, relative(it)) },
                        ).joinToString(" - "),
                    )
                },
                trailingContent = {
                    IconButton(onClick = { onRemove(device) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.adm_device_remove))
                    }
                },
            )
        }
    }
}

@Composable
private fun UserDevice.label(): String = name.ifBlank { app }.ifBlank { stringResource(R.string.adm_device_unnamed) }

@Composable
private fun SubscriptionsCard(subscriptions: List<SubscriptionView>) {
    val context = LocalContext.current
    SectionTitle(stringResource(R.string.adm_user_subscriptions))
    SectionCard {
        if (subscriptions.isEmpty()) Text(stringResource(R.string.adm_user_no_subscriptions), modifier = Modifier.padding(16.dp))
        subscriptions.forEach { sub ->
            ListItem(
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card),
                headlineContent = { Text(sub.plan ?: stringResource(R.string.adm_user_unknown_plan)) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.adm_subscription_line,
                            subscriptionStatus(sub.status),
                            context.date(sub.startDate),
                            if (sub.endDate > 0) context.date(sub.endDate) else "-",
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun PaymentsCard(payments: List<PaymentView>, onConfirm: (PaymentView) -> Unit, onCancel: (PaymentView) -> Unit) {
    val context = LocalContext.current
    SectionTitle(stringResource(R.string.adm_user_payments))
    SectionCard {
        if (payments.isEmpty()) Text(stringResource(R.string.adm_user_no_payments), modifier = Modifier.padding(16.dp))
        payments.forEach { payment ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("${payment.amount.toPlainString()} ${payment.currency}", style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.adm_payment_line, paymentStatus(payment.status), context.date(payment.createdAt)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                payment.transactionId?.let {
                    Text(
                        stringResource(R.string.adm_payment_transaction, listOfNotNull(payment.network, payment.token).joinToString(" ")),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MonoText(it)
                }
                if (payment.status == Payment.Status.PENDING && !payment.canConfirm) {
                    Text(
                        stringResource(R.string.adm_payment_confirm_on_web),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (payment.canConfirm || payment.canCancel) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (payment.canConfirm) {
                            TextButton(
                                onClick = { onConfirm(payment) },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.tertiary),
                            ) { Text(stringResource(R.string.adm_payment_confirm)) }
                        }
                        if (payment.canCancel) {
                            TextButton(
                                onClick = { onCancel(payment) },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) { Text(stringResource(R.string.adm_payment_cancel)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserChangeDialog(detail: UserDetail, change: UserChange, state: UsersUiState, viewModel: UsersViewModel) {
    val user = detail.user
    val name = user.name ?: shortId(user.id)
    when (change) {
        is UserChange.SetAdmin -> ConfirmDialog(
            title = stringResource(if (change.admin) R.string.adm_user_make_admin_title else R.string.adm_user_unmake_admin_title),
            body = stringResource(if (change.admin) R.string.adm_user_make_admin_body else R.string.adm_user_unmake_admin_body, name),
            action = stringResource(if (change.admin) R.string.adm_user_make_admin else R.string.adm_user_unmake_admin),
            busy = state.busy,
            error = state.error,
            onConfirm = { viewModel.confirm() },
            onCancel = viewModel::cancel,
        )
        is UserChange.RemoveDevice -> ConfirmDialog(
            title = stringResource(R.string.adm_device_remove_title),
            body = stringResource(R.string.adm_device_remove_body, change.device.label(), name),
            action = stringResource(R.string.adm_device_remove),
            busy = state.busy,
            error = state.error,
            onConfirm = { viewModel.confirm() },
            onCancel = viewModel::cancel,
            destructive = true,
        )
        is UserChange.ConfirmPayment -> ConfirmDialog(
            title = stringResource(R.string.adm_payment_confirm_title),
            body = stringResource(
                R.string.adm_payment_confirm_body,
                "${change.payment.amount.toPlainString()} ${change.payment.currency}",
                name,
                change.payment.transactionId.orEmpty(),
            ),
            action = stringResource(R.string.adm_payment_confirm),
            busy = state.busy,
            error = state.error,
            onConfirm = { viewModel.confirm() },
            onCancel = viewModel::cancel,
        )
        is UserChange.CancelPayment -> ConfirmDialog(
            title = stringResource(R.string.adm_payment_cancel_title),
            body = stringResource(R.string.adm_payment_cancel_body, "${change.payment.amount.toPlainString()} ${change.payment.currency}", name),
            action = stringResource(R.string.adm_payment_cancel),
            busy = state.busy,
            error = state.error,
            onConfirm = { viewModel.confirm() },
            onCancel = viewModel::cancel,
            destructive = true,
        )
        UserChange.Remove -> ConfirmByTypingDialog(
            title = stringResource(R.string.adm_user_remove_title),
            body = stringResource(R.string.adm_user_remove_body, name),
            code = confirmCode(user.id),
            action = stringResource(R.string.adm_user_remove),
            busy = state.busy,
            error = state.error,
            onConfirm = viewModel::confirm,
            onCancel = viewModel::cancel,
        )
    }
}

@Composable
private fun subscriptionStatus(status: Subscription.Status): String = stringResource(
    when (status) {
        Subscription.Status.PENDING -> R.string.adm_status_pending
        Subscription.Status.ACTIVE -> R.string.adm_status_active
        Subscription.Status.PAST_DUE -> R.string.adm_status_past_due
        Subscription.Status.EXPIRED -> R.string.adm_status_expired
        Subscription.Status.CANCELED -> R.string.adm_status_canceled
    },
)

@Composable
private fun paymentStatus(status: Payment.Status): String = stringResource(
    when (status) {
        Payment.Status.UNPAID -> R.string.adm_status_unpaid
        Payment.Status.PENDING -> R.string.adm_status_pending
        Payment.Status.CONFIRMED -> R.string.adm_status_confirmed
        Payment.Status.EXPIRED -> R.string.adm_status_expired
        Payment.Status.CANCELED -> R.string.adm_status_canceled
        Payment.Status.FAILED -> R.string.adm_status_failed
    },
)
