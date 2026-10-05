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
import io.bosonnetwork.director.client.FederatedService
import io.bosonnetwork.director.client.FederationProposal
import io.bosonnetwork.identity.core.boson.AdminSession
import io.bosonnetwork.identity.core.designsystem.component.shortId
import io.bosonnetwork.identity.core.model.AppError
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
    val name: String?,
    val endpoint: String?,
    val software: String?,
    val version: String?,
    /** Whether federation with it is in force (it may be paused). */
    val federated: Boolean,
    val reputation: Int,
    val updatedAt: Long,
)

data class ProposalView(
    val id: Long,
    val nodeId: String,
    val name: String?,
    /** Made by this node ([FederationProposal.Role.OFFER]) or received ([FederationProposal.Role.ANSWER]). */
    val role: FederationProposal.Role,
    val status: FederationProposal.Status,
    val proposedAt: Long,
)

data class ServiceView(val name: String, val endpoint: String?)

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
    /** Removing a federated node: confirmed by typing the first characters of its id. */
    data class RemoveNode(val node: FederatedNodeView) : FederationChange
    data class RemoveProposal(val proposal: ProposalView) : FederationChange
}

data class FederationUiState(
    /** Set while the propose dialog is open. */
    val propose: Proposal? = null,
    val change: FederationChange? = null,
    /** The services of the node whose detail is open. */
    val services: Pair<FederatedNodeView, Loaded<List<ServiceView>>>? = null,
    /** What became of the last proposal, said once. */
    val proposed: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Federation: the nodes this node federates with, their services, and the proposals made and
 * received; proposing federation to a node, removing a proposal or a node.
 */
@HiltViewModel
class FederationViewModel @Inject constructor(
    session: AdminSession,
    @ApplicationContext context: Context,
) : AdminSectionViewModel<FederationContent>(session, context) {

    private val _state = MutableStateFlow(FederationUiState())
    val state: StateFlow<FederationUiState> = _state.asStateFlow()

    private var servicesJob: Job? = null

    init {
        watchSession()
    }

    override suspend fun load(admin: DirectorAdmin): FederationContent =
        try {
            coroutineScope {
                val proposals = async { admin.listFederationProposals().await().items() }
                val nodes = admin.listFederatedNodes().await().items()
                FederationContent(
                    enabled = true,
                    nodes = nodes.map { it.toView() }.sortedWith(compareByDescending<FederatedNodeView> { it.federated }.thenBy { it.name ?: it.id }),
                    proposals = proposals.await().map { it.toView() }.sortedByDescending { it.proposedAt },
                )
            }
        } catch (e: Exception) {
            if (e.isNotEnabled()) FederationContent(enabled = false) else throw e
        }

    override fun onSessionEnded() {
        servicesJob?.cancel()
        _state.value = FederationUiState()
    }

    fun startPropose() = _state.update { it.copy(propose = Proposal(), error = null, proposed = null) }
    fun onProposeIdChange(value: String) = _state.update { s -> s.propose?.let { s.copy(propose = it.copy(nodeId = value), error = null) } ?: s }

    /** Proposes federation to the node typed. The node answers once the other one has. */
    fun propose() {
        val s = _state.value
        val id = s.propose?.validId ?: return
        if (s.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val node = session.withClient { it.proposeFederation(id).await() }.orElse(null)
                val outcome = node?.let { context.getString(R.string.adm_fed_proposed_federated, it.name.orElse(null) ?: shortId(it.id.toString())) }
                    ?: context.getString(R.string.adm_fed_proposed_sent)
                _state.update { it.copy(busy = false, propose = null, proposed = outcome) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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

    /** Shows the services [node] offers to the federation. */
    fun openServices(node: FederatedNodeView) {
        _state.update { it.copy(services = node to Loaded.Loading) }
        servicesJob?.cancel()
        servicesJob = viewModelScope.launch {
            val loaded = try {
                Loaded.Ready(session.withClient { it.listFederatedServices(Id.of(node.id)).await() }.map { it.toView() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loaded.Failed(failure(e))
            }
            _state.update { s -> if (s.services?.first == node) s.copy(services = node to loaded) else s }
        }
    }

    fun closeServices() {
        servicesJob?.cancel()
        _state.update { it.copy(services = null) }
    }

    fun ask(change: FederationChange) = _state.update { it.copy(change = change, error = null) }

    fun cancel() = _state.update { if (it.busy) it else it.copy(propose = null, change = null, error = null) }

    /** Makes the change asked for. Removing a node needs [typed] to match the first characters of its id. */
    fun confirm(typed: String = "") {
        val s = _state.value
        val change = s.change ?: return
        if (s.busy) return
        if (change is FederationChange.RemoveNode && !confirms(typed, confirmCode(change.node.id))) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                session.withClient { admin ->
                    when (change) {
                        is FederationChange.RemoveNode -> admin.removeFederatedNode(Id.of(change.node.id)).await()
                        is FederationChange.RemoveProposal -> admin.removeFederationProposal(change.proposal.id).await()
                    }
                }
                changeContent { content ->
                    when (change) {
                        is FederationChange.RemoveNode -> content.copy(nodes = content.nodes.filter { it.id != change.node.id })
                        is FederationChange.RemoveProposal -> content.copy(proposals = content.proposals.filter { it.id != change.proposal.id })
                    }
                }
                _state.update { it.copy(busy = false, change = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = failure(e)) }
            }
        }
    }
}

private fun FederatedNode.toView() = FederatedNodeView(
    id = id.toString(),
    name = name.orElse(null)?.takeIf { it.isNotBlank() },
    endpoint = apiEndpoint.orElse(null)?.takeIf { it.isNotBlank() },
    software = software.orElse(null)?.takeIf { it.isNotBlank() },
    version = version.orElse(null)?.takeIf { it.isNotBlank() },
    federated = isFederated,
    reputation = reputation,
    updatedAt = updatedAt,
)

private fun FederationProposal.toView() = ProposalView(
    id = id,
    nodeId = nodeId.toString(),
    name = name.orElse(null)?.takeIf { it.isNotBlank() },
    role = role,
    status = status,
    proposedAt = proposedAt,
)

private fun FederatedService.toView() = ServiceView(
    name = serviceName.orElse(null)?.takeIf { it.isNotBlank() } ?: serviceId.orElse(null) ?: peerId.toString(),
    endpoint = endpoint.orElse(null)?.takeIf { it.isNotBlank() },
)
