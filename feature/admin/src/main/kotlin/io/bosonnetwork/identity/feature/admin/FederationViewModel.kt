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
import io.bosonnetwork.Id
import io.bosonnetwork.director.client.DirectorAdmin
import io.bosonnetwork.director.client.FederatedNode
import io.bosonnetwork.director.client.FederatedNodeHealth
import io.bosonnetwork.director.client.FederationProposal
import io.bosonnetwork.identity.core.boson.AdminSession
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.service.Admission
import io.bosonnetwork.service.FederatedNodeDescriptor
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

data class FederatedNodeView(
    val id: String,
    val state: FederatedNode.State?,
    val origin: String?,
    val note: String?,
    val admission: Admission.Reason?,
    val reach: Admission.Reason?,
    val health: FederatedNodeHealth?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ProposalView(
    val id: Long,
    val nodeId: String,
    /** Made by this node ([FederationProposal.Role.OFFER]) or received ([FederationProposal.Role.ANSWER]). */
    val role: FederationProposal.Role?,
    val status: FederationProposal.Status?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val answeredAt: Long,
) {
    /** Pending incoming proposals must be answered; unknown states do not imply a safe removal. */
    val canRemove: Boolean
        get() = role != null && status != null && (status != FederationProposal.Status.PENDING || role == FederationProposal.Role.OFFER)
}

/** The federation, or [enabled] false when the node doesn't federate. */
data class FederationContent(
    val enabled: Boolean,
    val nodes: List<FederatedNodeView> = emptyList(),
    val proposals: List<ProposalView> = emptyList(),
)

/** Proposing federation to a node, by its id: the node finds it on the DHT. */
data class Proposal(val nodeId: String = "") {
    val validId: Id? get() = runCatching { Id.of(nodeId.trim()) }.getOrNull()
}

sealed interface FederationChange {
    /** Removing a relationship: confirmed by typing the first characters of the node id. */
    data class RemoveNode(val node: FederatedNodeView) : FederationChange
    data class RemoveProposal(val proposal: ProposalView) : FederationChange
}

data class FederationUiState(
    /** Set while the propose dialog is open. */
    val propose: Proposal? = null,
    val change: FederationChange? = null,
    /** The signed description requested for a node; null content means unavailable. */
    val descriptor: Pair<String, Loaded<FederatedNodeDescriptor?>>? = null,
    /** What became of the last proposal, said once. */
    val proposed: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Relationships, their descriptors and the proposals this node makes and receives. */
@HiltViewModel
class FederationViewModel @Inject constructor(
    session: AdminSession,
    @ApplicationContext context: Context,
) : AdminSectionViewModel<FederationContent>(session, context) {

    private val _state = MutableStateFlow(FederationUiState())
    val state: StateFlow<FederationUiState> = _state.asStateFlow()

    private var descriptorJob: Job? = null
    private var descriptorRequest = 0L
    private var actionJob: Job? = null

    init {
        watchSession()
    }

    override suspend fun load(admin: DirectorAdmin): FederationContent =
        try {
            coroutineScope {
                val proposals = async { admin.listFederationProposals(null, null).await().items() }
                val nodes = admin.listFederatedNodes().await().items()
                FederationContent(
                    enabled = true,
                    nodes = nodes.map { it.toView() },
                    proposals = proposals.await().map { it.toView() },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isNotEnabled()) FederationContent(enabled = false) else throw e
        }

    override fun onSessionEnded() {
        descriptorRequest++
        descriptorJob?.cancel()
        actionJob?.cancel()
        _state.value = FederationUiState()
    }

    fun startPropose() {
        if (session.state.value == null || (content.value as? Loaded.Ready)?.value?.enabled != true) return
        _state.update { it.copy(propose = Proposal(), error = null, proposed = null) }
    }

    fun onProposeIdChange(value: String) = _state.update { s -> s.propose?.let { s.copy(propose = it.copy(nodeId = value), error = null) } ?: s }

    /** Proposes federation and reports the actual result of its first attempt. */
    fun propose() {
        val open = session.state.value ?: return
        val s = _state.value
        val id = s.propose?.validId ?: return
        if (s.busy) return
        _state.update { it.copy(busy = true, error = null) }
        actionJob = viewModelScope.launch {
            try {
                val proposal = session.withClient {
                    if (session.state.value !== open) throw CancellationException()
                    it.proposeFederation(id, null).await()
                }
                if (session.state.value !== open) return@launch
                val outcome = when (proposal.status.orElse(null)) {
                    FederationProposal.Status.ACCEPTED -> if (proposal.role.orElse(null) == FederationProposal.Role.ANSWER)
                        R.string.adm_fed_proposed_crossed else R.string.adm_fed_proposed_accepted
                    FederationProposal.Status.DECLINED -> R.string.adm_fed_proposed_declined
                    FederationProposal.Status.PENDING -> R.string.adm_fed_proposed_sent
                    else -> R.string.adm_fed_proposed_other
                }
                _state.update { it.copy(busy = false, propose = null, proposed = context.getString(outcome)) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session.state.value !== open) return@launch
                val message = when (e) {
                    is AppError.Forbidden -> context.getString(R.string.adm_fed_propose_blacklisted)
                    is AppError.Conflict -> context.getString(R.string.adm_fed_propose_already)
                    is AppError.RateLimited -> context.getString(R.string.adm_fed_propose_cooldown)
                    else -> failure(e)
                }
                _state.update { it.copy(busy = false, error = message) }
            }
        }
    }

    fun proposedSeen() = _state.update { it.copy(proposed = null) }

    /** Fetches what [nodeId] says it runs, verified by the Director, only when asked. */
    fun openDescriptor(nodeId: String) {
        val open = session.state.value ?: return
        val request = ++descriptorRequest
        descriptorJob?.cancel()
        _state.update { it.copy(descriptor = nodeId to Loaded.Loading) }
        descriptorJob = viewModelScope.launch {
            val loaded = try {
                Loaded.Ready(session.withClient {
                    if (session.state.value !== open) throw CancellationException()
                    it.getFederatedNodeDescriptor(Id.of(nodeId)).await().orElse(null)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loaded.Failed(failure(e))
            }
            if (session.state.value === open && request == descriptorRequest)
                _state.update { it.copy(descriptor = nodeId to loaded) }
        }
    }

    fun closeDescriptor() {
        descriptorRequest++
        descriptorJob?.cancel()
        _state.update { it.copy(descriptor = null) }
    }

    fun ask(change: FederationChange) {
        if (session.state.value == null) return
        if (change is FederationChange.RemoveProposal && !change.proposal.canRemove) return
        _state.update { it.copy(change = change, error = null) }
    }

    fun cancel() = _state.update { if (it.busy) it else it.copy(propose = null, change = null, error = null) }

    /** Makes the change asked for. Removing a relationship needs its node's confirmation code. */
    fun confirm(typed: String = "") {
        val open = session.state.value ?: return
        val s = _state.value
        val change = s.change ?: return
        if (s.busy) return
        if (change is FederationChange.RemoveNode && !confirms(typed, confirmCode(change.node.id))) return
        if (change is FederationChange.RemoveProposal && !change.proposal.canRemove) return
        _state.update { it.copy(busy = true, error = null) }
        actionJob = viewModelScope.launch {
            try {
                session.withClient { admin ->
                    if (session.state.value !== open) throw CancellationException()
                    when (change) {
                        is FederationChange.RemoveNode -> admin.removeFederatedNode(Id.of(change.node.id)).await()
                        is FederationChange.RemoveProposal -> admin.removeFederationProposal(change.proposal.id).await()
                    }
                }
                if (session.state.value !== open) return@launch
                changeContent { content ->
                    when (change) {
                        is FederationChange.RemoveNode -> content.copy(nodes = content.nodes.filter { it.id != change.node.id })
                        is FederationChange.RemoveProposal -> content.copy(proposals = content.proposals.filter { it.id != change.proposal.id })
                    }
                }
                _state.update { it.copy(busy = false, change = null) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session.state.value !== open) return@launch
                _state.update { it.copy(busy = false, error = failure(e)) }
            }
        }
    }
}

private fun FederatedNode.toView() = FederatedNodeView(
    id = id.toString(),
    state = state.orElse(null),
    origin = origin.orElse(null),
    note = note.orElse(null),
    admission = admission.orElse(null),
    reach = reach.orElse(null),
    health = health.orElse(null),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun FederationProposal.toView() = ProposalView(
    id = id,
    nodeId = nodeId.toString(),
    role = role.orElse(null),
    status = status.orElse(null),
    note = note.orElse(null),
    createdAt = createdAt,
    updatedAt = updatedAt,
    answeredAt = answeredAt,
)
