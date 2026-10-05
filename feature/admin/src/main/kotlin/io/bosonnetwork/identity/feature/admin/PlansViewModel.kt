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

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.director.client.DirectorAdmin
import io.bosonnetwork.director.client.Feature
import io.bosonnetwork.director.client.Plan
import io.bosonnetwork.director.client.PlanUpdate
import io.bosonnetwork.identity.core.boson.AdminSession
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

data class PlanView(
    val id: Int,
    val name: String,
    val description: String?,
    val price: BigDecimal,
    val currency: String,
    val cycle: Plan.Cycle,
    val free: Boolean,
    val active: Boolean,
)

/** What a plan gives with one service: a document the service reads, shown as it is. */
data class FeatureView(val id: Int, val planId: Int, val serviceId: String, val feature: Map<String, Any?>)

data class PlansContent(val plans: List<PlanView>, val features: List<FeatureView>) {
    fun featuresOf(planId: Int): List<FeatureView> = features.filter { it.planId == planId }
}

/** Editing a plan's name and description, as typed. */
data class PlanEdit(val plan: PlanView, val name: String, val description: String) {
    val validName: String? get() = name.trim().takeIf { it.isNotEmpty() }
}

/** A plan change waiting for confirmation. */
sealed interface PlanChange {
    /** Deactivating: confirmed by typing the plan's name. */
    data class Deactivate(val plan: PlanView) : PlanChange
    data class Reactivate(val plan: PlanView) : PlanChange
}

data class PlansUiState(
    val edit: PlanEdit? = null,
    val change: PlanChange? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Plans: the node's plans, active or not, with the features each gives (read-only). Names and
 * descriptions can be edited, and a plan deactivated (or brought back). Prices and feature documents
 * are edited on the node's web dashboard: their forms are heavy and rarely needed.
 */
@HiltViewModel
class PlansViewModel @Inject constructor(
    session: AdminSession,
    @ApplicationContext context: Context,
) : AdminSectionViewModel<PlansContent>(session, context) {

    private val _state = MutableStateFlow(PlansUiState())
    val state: StateFlow<PlansUiState> = _state.asStateFlow()

    init {
        watchSession()
    }

    override suspend fun load(admin: DirectorAdmin): PlansContent = coroutineScope {
        val features = async { admin.listFeatures().await() }
        val plans = admin.listPlans().await()
        PlansContent(
            // Active plans first, then by price.
            plans.map { it.toView() }.sortedWith(compareByDescending<PlanView> { it.active }.thenBy { it.price }.thenBy { it.name }),
            features.await().map { it.toView() },
        )
    }

    override fun onSessionEnded() {
        _state.value = PlansUiState()
    }

    fun startEdit(plan: PlanView) = _state.update { it.copy(edit = PlanEdit(plan, plan.name, plan.description.orEmpty()), error = null) }
    fun onNameChange(value: String) = _state.update { s -> s.edit?.let { s.copy(edit = it.copy(name = value), error = null) } ?: s }
    fun onDescriptionChange(value: String) = _state.update { s -> s.edit?.let { s.copy(edit = it.copy(description = value), error = null) } ?: s }

    /** Saves the changed fields. */
    fun save() {
        val s = _state.value
        val edit = s.edit ?: return
        if (s.busy) return
        val name = edit.validName ?: return
        val description = edit.description.trim().ifEmpty { null }
        val update = PlanUpdate()
        if (name != edit.plan.name) update.name(name)
        if (description != edit.plan.description) update.description(description)
        if (update.isEmpty) {
            _state.update { it.copy(edit = null) }
            return
        }
        perform(update = { it.updatePlan(edit.plan.id, update).await() }) { plan ->
            if (plan.id == edit.plan.id) plan.copy(name = name, description = description) else plan
        }
    }

    /** Asks to deactivate or reactivate a plan; the dialog confirms it. */
    fun ask(change: PlanChange) = _state.update { it.copy(change = change, error = null) }

    fun cancel() = _state.update { if (it.busy) it else it.copy(edit = null, change = null, error = null) }

    /** Makes the change asked for. Deactivating needs [typed] to be the plan's name. */
    fun confirm(typed: String = "") {
        val s = _state.value
        if (s.busy) return
        when (val change = s.change ?: return) {
            is PlanChange.Deactivate -> {
                if (!confirms(typed, change.plan.name)) return
                perform(update = { it.deactivatePlan(change.plan.id).await() }) { plan ->
                    if (plan.id == change.plan.id) plan.copy(active = false) else plan
                }
            }
            is PlanChange.Reactivate -> perform(update = { it.updatePlan(change.plan.id, PlanUpdate().active(true)).await() }) { plan ->
                if (plan.id == change.plan.id) plan.copy(active = true) else plan
            }
        }
    }

    private fun perform(update: suspend (DirectorAdmin) -> Any?, changed: (PlanView) -> PlanView) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                session.withClient { update(it) }
                changeContent { content -> content.copy(plans = content.plans.map(changed)) }
                _state.update { it.copy(busy = false, edit = null, change = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = failure(e)) }
            }
        }
    }
}

private fun Plan.toView() = PlanView(
    id = id,
    name = name,
    description = description.orElse(null)?.takeIf { it.isNotBlank() },
    price = price,
    currency = currency,
    cycle = cycle,
    free = isFree,
    active = isActive,
)

private fun Feature.toView() = FeatureView(id, planId, serviceId, feature)
