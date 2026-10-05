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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.director.client.Plan
import io.bosonnetwork.identity.core.designsystem.component.SectionCard
import io.bosonnetwork.identity.core.designsystem.theme.brand
import org.json.JSONObject

/** Plans: each plan with its features; names and descriptions editable, prices on the web. */
@Composable
internal fun PlansTab(refreshes: Int, viewModel: PlansViewModel = hiltViewModel()) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    RefreshOn(refreshes, viewModel::refresh)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.adm_plans_web),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LoadedContent(content, onRetry = viewModel::refresh) { plans ->
                if (plans.plans.isEmpty()) Text(stringResource(R.string.adm_plans_none))
                plans.plans.forEach { plan ->
                    PlanCard(
                        plan,
                        plans.featuresOf(plan.id),
                        onEdit = { viewModel.startEdit(plan) },
                        onDeactivate = { viewModel.ask(PlanChange.Deactivate(plan)) },
                        onReactivate = { viewModel.ask(PlanChange.Reactivate(plan)) },
                    )
                }
            }
        }
    }

    state.edit?.let { edit -> EditPlanDialog(edit, state, viewModel) }
    when (val change = state.change) {
        is PlanChange.Deactivate -> ConfirmByTypingDialog(
            title = stringResource(R.string.adm_plan_deactivate_title),
            body = stringResource(R.string.adm_plan_deactivate_body, change.plan.name),
            code = change.plan.name,
            action = stringResource(R.string.adm_plan_deactivate),
            busy = state.busy,
            error = state.error,
            onConfirm = viewModel::confirm,
            onCancel = viewModel::cancel,
        )
        is PlanChange.Reactivate -> ConfirmDialog(
            title = stringResource(R.string.adm_plan_reactivate_title),
            body = stringResource(R.string.adm_plan_reactivate_body, change.plan.name),
            action = stringResource(R.string.adm_plan_reactivate),
            busy = state.busy,
            error = state.error,
            onConfirm = { viewModel.confirm() },
            onCancel = viewModel::cancel,
        )
        null -> Unit
    }
}

@Composable
private fun PlanCard(
    plan: PlanView,
    features: List<FeatureView>,
    onEdit: () -> Unit,
    onDeactivate: () -> Unit,
    onReactivate: () -> Unit,
) {
    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(plan.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                if (plan.active) {
                    AdminBadge(stringResource(R.string.adm_plan_active))
                } else {
                    Text(stringResource(R.string.adm_plan_inactive), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                if (plan.free) {
                    stringResource(R.string.adm_plan_free)
                } else {
                    stringResource(
                        if (plan.cycle == Plan.Cycle.ANNUALLY) R.string.adm_plan_price_year else R.string.adm_plan_price_month,
                        plan.price.toPlainString(),
                        plan.currency,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            plan.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (features.isNotEmpty()) {
                Text(
                    stringResource(R.string.adm_plan_features),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                features.forEach { feature ->
                    Text(feature.serviceId, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    MonoText(featureText(feature.feature))
                }
            }
        }
        Row(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
            TextButton(onClick = onEdit, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)) {
                Text(stringResource(R.string.adm_plan_edit))
            }
            if (plan.active) {
                TextButton(onClick = onDeactivate, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.adm_plan_deactivate))
                }
            } else {
                TextButton(onClick = onReactivate, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)) {
                    Text(stringResource(R.string.adm_plan_reactivate))
                }
            }
        }
    }
}

/** A feature document, as indented JSON. */
private fun featureText(feature: Map<String, Any?>): String =
    runCatching { JSONObject(feature).toString(2) }.getOrElse { feature.toString() }

@Composable
private fun EditPlanDialog(edit: PlanEdit, state: PlansUiState, viewModel: PlansViewModel) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) viewModel.cancel() },
        containerColor = MaterialTheme.brand.card,
        title = { Text(stringResource(R.string.adm_plan_edit_title)) },
        text = {
            ObscuredTouchGuard()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = edit.name,
                    onValueChange = viewModel::onNameChange,
                    label = { Text(stringResource(R.string.adm_plan_name)) },
                    singleLine = true,
                    enabled = !state.busy,
                    isError = edit.validName == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = edit.description,
                    onValueChange = viewModel::onDescriptionChange,
                    label = { Text(stringResource(R.string.adm_plan_description)) },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.adm_plan_edit_note), style = MaterialTheme.typography.bodySmall)
                ErrorText(state.error)
            }
        },
        confirmButton = {
            if (state.busy) {
                Busy()
            } else {
                TextButton(
                    onClick = viewModel::save,
                    enabled = edit.validName != null,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.tertiary),
                ) { Text(stringResource(R.string.adm_save)) }
            }
        },
        dismissButton = { TextButton(onClick = viewModel::cancel, enabled = !state.busy) { Text(stringResource(R.string.adm_cancel)) } },
    )
}
