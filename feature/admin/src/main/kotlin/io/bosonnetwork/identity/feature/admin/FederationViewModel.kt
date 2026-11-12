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
import io.bosonnetwork.director.client.FederationProposal
import io.bosonnetwork.director.client.FederatedNodeHealth
import io.bosonnetwork.identity.core.boson.AdminSession
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.service.FederatedNodeDescriptor
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

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
    val canAnswer: Boolean
        get() = role == FederationProposal.Role.ANSWER && status == FederationProposal.Status.PENDING

    /** Pending incoming proposals must be answered; unknown states do not imply a safe removal. */
    val canRemove: Boolean
        get() = role != null && status != null && (status != FederationProposal.Status.PENDING || role == FederationProposal.Role.OFFER)
}

/** Proposal history, or [enabled] false when the node does not federate. */
data class FederationContent(
    val enabled: Boolean,
    val proposals: List<ProposalView> = emptyList(),
    val page: Long = 1,
    val totalPages: Long = 0,
    val total: Long = 0,
) {
    val more: Boolean get() = page < totalPages
}

/** Draft search is separate from the filters applied to the server page. */
data class ProposalListState(
    val query: String = "",
    val appliedQuery: String = "",
    val role: FederationProposal.Role? = null,
    val status: FederationProposal.Status? = null,
    val invalid: Boolean = false,
    val pending: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val moreError: String? = null,
)

enum class FederationView { NODES, PROPOSALS, ACTIVITY, HOSTS }

enum class FederationDecision { ADMIT, SUSPEND }

/** An explicit operator decision, with an existing row fixed to its node id. */
data class NodeDecision(
    val decision: FederationDecision,
    val nodeId: String = "",
    val note: String = "",
    val existing: Boolean = false,
    val confirming: Boolean = false,
) {
    val validId: Id? get() = runCatching { Id.of(nodeId.trim()) }.getOrNull()
    val valid: Boolean get() = validId != null && note.length <= 256
    val code: String get() = validId?.toString()?.let(::confirmCode).orEmpty()
}

/** Proposing federation to a node, by its id: the node finds it on the DHT. */
data class Proposal(val nodeId: String = "", val note: String = "") {
    val validId: Id? get() = runCatching { Id.of(nodeId.trim()) }.getOrNull()
    val valid: Boolean get() = validId != null && note.length <= 256
}

sealed interface FederationChange {
    /** Removing a relationship: confirmed by typing the first characters of the node id. */
    data class RemoveNode(val node: FederatedNodeView) : FederationChange
    data class RemoveProposal(val proposal: ProposalView) : FederationChange
    data class AnswerProposal(val proposal: ProposalView, val accept: Boolean) : FederationChange
}

data class FederationUiState(
    val view: FederationView = FederationView.NODES,
    /** Invalidates relationships and observations after an operation, using each view's current query. */
    val nodeChanges: Int = 0,
    val decision: NodeDecision? = null,
    /** Set while the propose dialog is open. */
    val propose: Proposal? = null,
    val change: FederationChange? = null,
    /** The signed description requested for a node; null content means unavailable. */
    val descriptor: Pair<String, Loaded<FederatedNodeDescriptor?>>? = null,
    /** What became of the last proposal, said once. */
    val proposed: String? = null,
    val notice: String? = null,
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

    private val _proposals = MutableStateFlow(ProposalListState())
    val proposals: StateFlow<ProposalListState> = _proposals.asStateFlow()
    private val _review = MutableStateFlow<Loaded<Long>>(Loaded.Idle)
    val review: StateFlow<Loaded<Long>> = _review.asStateFlow()

    private var searchJob: Job? = null
    private var moreJob: Job? = null
    private var proposalRequest = 0L
    private var reviewJob: Job? = null
    private var reviewRequest = 0L
    private var descriptorJob: Job? = null
    private var descriptorRequest = 0L
    private var actionJob: Job? = null
    private var proposalsDirty = true

    init {
        watchSession(loadOnStart = false)
    }

    override suspend fun load(admin: DirectorAdmin): FederationContent {
        val current = proposalRequest
        val open = currentSession()
        val filters = _proposals.value
        return try {
            val result = admin.listFederationProposals(filters.appliedQuery.takeIf { it.isNotEmpty() }?.let(Id::of),
                filters.role, filters.status, 1, FEDERATION_PAGE).await()
            FederationContent(true, result.items().map { it.toView() }, result.page(), result.totalPages(), result.totalItems())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isNotEnabled()) FederationContent(enabled = false) else throw e
        } finally {
            if (currentSession() === open && current == proposalRequest)
                _proposals.update { it.copy(pending = false, refreshing = false) }
        }
    }

    override fun onSessionStarted() = refreshReviewCount()

    override fun onSessionEnded() {
        invalidateProposals()
        reviewRequest++
        reviewJob?.cancel()
        _review.value = Loaded.Idle
        _proposals.value = ProposalListState()
        descriptorRequest++
        descriptorJob?.cancel()
        actionJob?.cancel()
        proposalsDirty = true
        _state.value = FederationUiState()
    }

    fun selectView(view: FederationView) {
        if (currentSession() == null || view == _state.value.view) return
        _state.update { it.copy(view = view) }
        if (view == FederationView.PROPOSALS && (proposalsDirty || content.value is Loaded.Idle)) refreshProposals()
    }

    fun onProposalQueryChange(value: String) {
        if (currentSession() == null || value == _proposals.value.query) return
        invalidateProposals()
        val invalid = value.isNotBlank() && runCatching { Id.of(value.trim()) }.isFailure
        _proposals.update { it.copy(query = value, invalid = invalid, pending = !invalid,
            refreshing = false, loadingMore = false, moreError = null) }
        if (!invalid) searchJob = viewModelScope.launch {
            delay(250)
            searchJob = null
            refreshProposals()
        }
    }

    fun clearProposalSearch() {
        onProposalQueryChange("")
        refreshProposals()
    }

    fun setProposalFilters(role: FederationProposal.Role?, status: FederationProposal.Status?) {
        if (currentSession() == null || (role == _proposals.value.role && status == _proposals.value.status)) return
        invalidateProposals()
        _proposals.update { it.copy(role = role, status = status, pending = !it.invalid,
            refreshing = false, loadingMore = false, moreError = null) }
        refreshProposals()
    }

    fun refreshProposals() {
        if (currentSession() == null || _proposals.value.invalid) return
        searchJob?.cancel()
        searchJob = null
        proposalRequest++
        moreJob?.cancel()
        val filters = _proposals.value
        val keep = content.value is Loaded.Ready && !filters.pending && filters.query.trim() == filters.appliedQuery
        _proposals.update { it.copy(appliedQuery = filters.query.trim(), pending = !keep, refreshing = keep,
            loadingMore = false, moreError = null) }
        proposalsDirty = false
        refresh()
    }

    fun loadMoreProposals() {
        val open = currentSession() ?: return
        val pages = (content.value as? Loaded.Ready)?.value ?: return
        val filters = _proposals.value
        if (!pages.enabled || !pages.more || filters.pending || filters.invalid || filters.refreshing || filters.loadingMore) return
        val current = proposalRequest
        val next = pages.page + 1
        _proposals.update { it.copy(loadingMore = true, moreError = null) }
        moreJob = viewModelScope.launch {
            try {
                val result = session.withClient { admin ->
                    if (currentSession() !== open || current != proposalRequest) throw CancellationException()
                    admin.listFederationProposals(filters.appliedQuery.takeIf { it.isNotEmpty() }?.let(Id::of),
                        filters.role, filters.status, next, FEDERATION_PAGE).await()
                }
                if (currentSession() !== open || current != proposalRequest) return@launch
                if (next > result.totalPages()) {
                    moreJob = null
                    refreshProposals()
                    return@launch
                }
                changeContent { loaded ->
                    val known = loaded.proposals.map { it.id }.toSet()
                    FederationContent(true, loaded.proposals + result.items().map { it.toView() }.filter { it.id !in known },
                        result.page(), result.totalPages(), result.totalItems())
                }
                _proposals.update { it.copy(loadingMore = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentSession() === open && current == proposalRequest) {
                    if (e.isNotEnabled()) changeContent { FederationContent(enabled = false) }
                    _proposals.update { it.copy(loadingMore = false, moreError = if (e.isNotEnabled()) null else failure(e)) }
                }
            }
        }
    }

    private fun invalidateProposals() {
        proposalRequest++
        invalidateLoad()
        searchJob?.cancel()
        searchJob = null
        moreJob?.cancel()
    }

    /** This count always uses all nodes, independently of the visible query and filters. */
    fun refreshReviewCount() {
        val open = currentSession() ?: return
        val current = ++reviewRequest
        reviewJob?.cancel()
        if (_review.value !is Loaded.Ready) _review.value = Loaded.Loading
        reviewJob = viewModelScope.launch {
            val result = try {
                Loaded.Ready(session.withClient { admin ->
                    if (currentSession() !== open) throw CancellationException()
                    admin.listFederationProposals(null, FederationProposal.Role.ANSWER, FederationProposal.Status.PENDING, 1, 1).await().totalItems()
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isNotEnabled()) Loaded.Idle else Loaded.Failed(failure(e))
            }
            if (currentSession() === open && current == reviewRequest) _review.value = result
        }
    }

    fun reviewPending() {
        if (currentSession() == null) return
        invalidateProposals()
        _proposals.value = ProposalListState(role = FederationProposal.Role.ANSWER, status = FederationProposal.Status.PENDING, pending = true)
        _state.update { it.copy(view = FederationView.PROPOSALS) }
        refreshProposals()
    }

    /** Nodes and proposals affect one another; hidden history is reloaded when selected. */
    private fun changed() {
        proposalsDirty = true
        invalidateProposals()
        _proposals.update { it.copy(loadingMore = false, refreshing = false, moreError = null) }
        refreshReviewCount()
        _state.update { it.copy(nodeChanges = it.nodeChanges + 1) }
        if (_state.value.view == FederationView.PROPOSALS) refreshProposals()
    }

    /** A disabled API invalidates the view instead of leaving an actionable form over old data. */
    private fun handleDisabled(e: Exception): Boolean {
        if (!e.isNotEnabled()) return false
        _state.update { it.copy(decision = null, propose = null, change = null, busy = false, error = null) }
        changed()
        return true
    }

    fun startDecision(decision: FederationDecision, node: FederatedNodeView? = null) {
        if (currentSession() == null || _state.value.busy) return
        _state.update { it.copy(decision = NodeDecision(decision, node?.id.orEmpty(), node?.note.orEmpty(), node != null), error = null) }
    }

    fun onDecisionIdChange(value: String) = _state.update { s ->
        s.decision?.takeIf { !it.existing && !s.busy }
            ?.let { s.copy(decision = it.copy(nodeId = value, confirming = false), error = null) } ?: s
    }

    fun onDecisionNoteChange(value: String) = _state.update { s ->
        s.decision?.takeIf { !s.busy }?.let { s.copy(decision = it.copy(note = value), error = null) } ?: s
    }

    fun saveDecision(typed: String = "") {
        val open = currentSession() ?: return
        val s = _state.value
        val form = s.decision ?: return
        val id = form.validId ?: return
        if (s.busy || !form.valid) return
        if (form.decision == FederationDecision.SUSPEND) {
            if (!form.confirming) {
                _state.update { it.copy(decision = form.copy(confirming = true)) }
                return
            }
            if (!confirms(typed, form.code)) return
        }
        _state.update { it.copy(busy = true, error = null) }
        actionJob = viewModelScope.launch {
            try {
                session.withClient { admin ->
                    if (session.state.value !== open) throw CancellationException()
                    val note = form.note.trim().ifEmpty { null }
                    when (form.decision) {
                        FederationDecision.ADMIT -> admin.admitFederatedNode(id, note).await()
                        FederationDecision.SUSPEND -> admin.suspendFederatedNode(id, note).await()
                    }
                }
                if (session.state.value !== open) return@launch
                _state.update { it.copy(decision = null, busy = false) }
                changed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session.state.value !== open || handleDisabled(e)) return@launch
                _state.update { it.copy(busy = false, error = failure(e)) }
            }
        }
    }

    fun startPropose() = openProposal("")

    /** The server decides whether a proposal could help this observed node's demand. */
    fun startActivityProposal(health: FederatedNodeHealth) {
        if (!health.isProposable) return
        openProposal(health.nodeId.toString())
    }

    private fun openProposal(nodeId: String) {
        if (currentSession() == null || _state.value.busy) return
        if (_state.value.view == FederationView.PROPOSALS && (content.value as? Loaded.Ready)?.value?.enabled == false) return
        _state.update { it.copy(propose = Proposal(nodeId), error = null, proposed = null) }
    }

    fun onProposeIdChange(value: String) = _state.update { s ->
        s.propose?.takeIf { !s.busy }?.let { s.copy(propose = it.copy(nodeId = value), error = null) } ?: s
    }

    fun onProposeNoteChange(value: String) = _state.update { s ->
        s.propose?.takeIf { !s.busy }?.let { s.copy(propose = it.copy(note = value), error = null) } ?: s
    }

    /** Proposes federation and reports the actual result of its first attempt. */
    fun propose() {
        val open = currentSession() ?: return
        val s = _state.value
        val id = s.propose?.validId ?: return
        if (s.busy || s.propose?.valid != true) return
        val note = s.propose.note.trim().ifEmpty { null }
        _state.update { it.copy(busy = true, error = null) }
        actionJob = viewModelScope.launch {
            try {
                val proposal = session.withClient {
                    if (session.state.value !== open) throw CancellationException()
                    it.proposeFederation(id, note).await()
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
                changed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session.state.value !== open || handleDisabled(e)) return@launch
                val message = when (e) {
                    is AppError.Forbidden -> context.getString(R.string.adm_fed_propose_blacklisted)
                    is AppError.Conflict -> context.getString(R.string.adm_fed_propose_already)
                    is AppError.RateLimited -> context.getString(R.string.adm_fed_propose_cooldown)
                    else -> failure(e)
                }
                _state.update { it.copy(busy = false, error = message) }
                if (e is AppError.Conflict) changed()
            }
        }
    }

    fun proposedSeen() = _state.update { it.copy(proposed = null) }

    fun noticeSeen() = _state.update { it.copy(notice = null) }

    /** Fetches what [nodeId] says it runs, verified by the Director, only when asked. */
    fun openDescriptor(nodeId: String) {
        val open = currentSession() ?: return
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
        if (currentSession() == null || _state.value.busy) return
        if (change is FederationChange.RemoveProposal && !change.proposal.canRemove) return
        if (change is FederationChange.AnswerProposal && !change.proposal.canAnswer) return
        _state.update { it.copy(change = change, error = null) }
    }

    fun cancel() = _state.update { if (it.busy) it else it.copy(propose = null, decision = null, change = null, error = null) }

    /** Makes the change asked for. Removing a relationship needs its node's confirmation code. */
    fun confirm(typed: String = "") {
        val open = currentSession() ?: return
        val s = _state.value
        val change = s.change ?: return
        if (s.busy) return
        if (change is FederationChange.RemoveNode && !confirms(typed, confirmCode(change.node.id))) return
        if (change is FederationChange.RemoveProposal && !change.proposal.canRemove) return
        if (change is FederationChange.AnswerProposal && !change.proposal.canAnswer) return
        _state.update { it.copy(busy = true, error = null) }
        actionJob = viewModelScope.launch {
            try {
                session.withClient { admin ->
                    if (session.state.value !== open) throw CancellationException()
                    when (change) {
                        is FederationChange.RemoveNode -> admin.removeFederatedNode(Id.of(change.node.id)).await()
                        is FederationChange.RemoveProposal -> admin.removeFederationProposal(change.proposal.id).await()
                        is FederationChange.AnswerProposal -> {
                            if (change.accept) admin.acceptFederationProposal(change.proposal.id).await()
                            else admin.declineFederationProposal(change.proposal.id).await()
                        }
                    }
                }
                if (session.state.value !== open) return@launch
                _state.update { it.copy(busy = false, change = null) }
                changed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session.state.value !== open || handleDisabled(e)) return@launch
                if (change !is FederationChange.RemoveNode && (e is AppError.Conflict || e is AppError.NotFound)) {
                    _state.update { it.copy(busy = false, change = null, notice = context.getString(R.string.adm_fed_proposal_changed)) }
                    changed()
                } else {
                    _state.update { it.copy(busy = false, error = failure(e)) }
                }
            }
        }
    }
}

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
