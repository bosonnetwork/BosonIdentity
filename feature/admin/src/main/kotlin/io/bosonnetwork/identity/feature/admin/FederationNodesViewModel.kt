/*
 * Copyright (c) 2026 -      bosonnetwork.io
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
import io.bosonnetwork.identity.core.boson.AdminSession
import io.bosonnetwork.service.Admission
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

data class NodePages(
    val enabled: Boolean,
    val nodes: List<FederatedNodeView> = emptyList(),
    val page: Long = 1,
    val totalPages: Long = 0,
    val total: Long = 0,
) {
    val more: Boolean get() = page < totalPages
}

data class FederationNodesUiState(
    val query: String = "",
    val appliedQuery: String = "",
    val invalid: Boolean = false,
    /** A new query is waiting for its debounce or first page. Old rows are hidden. */
    val pending: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val moreError: String? = null,
)

const val FEDERATION_PAGE = 20L

/** Existing relationships, filtered by the server before paging; all work belongs to admin mode. */
@HiltViewModel
class FederationNodesViewModel @Inject constructor(
    session: AdminSession,
    @ApplicationContext context: Context,
) : AdminSectionViewModel<NodePages>(session, context) {
    private val _state = MutableStateFlow(FederationNodesUiState())
    val state: StateFlow<FederationNodesUiState> = _state.asStateFlow()

    private var search: Job? = null
    private var more: Job? = null
    private var generation = 0L

    init {
        watchSession()
    }

    override suspend fun load(admin: DirectorAdmin): NodePages {
        val current = generation
        val open = currentSession()
        val query = _state.value.appliedQuery
        return try {
            val result = admin.listFederatedNodes(query.takeIf { it.isNotEmpty() }?.let(Id::of), 1, FEDERATION_PAGE).await()
            NodePages(true, result.items().map { it.toView() }, result.page(), result.totalPages(), result.totalItems())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isNotEnabled()) NodePages(enabled = false) else throw e
        } finally {
            if (current == generation && currentSession() === open)
                _state.update { it.copy(pending = false, refreshing = false) }
        }
    }

    override fun onSessionEnded() {
        generation++
        search?.cancel()
        more?.cancel()
        _state.value = FederationNodesUiState()
    }

    /** Invalidates old completions immediately, before starting the new query's debounce. */
    fun onQueryChange(value: String) {
        if (currentSession() == null || value == _state.value.query) return
        invalidateRequests()
        val invalid = value.isNotBlank() && runCatching { Id.of(value.trim()) }.isFailure
        _state.update { it.copy(query = value, invalid = invalid, pending = !invalid,
            refreshing = false, loadingMore = false, moreError = null) }
        if (!invalid) search = viewModelScope.launch {
            delay(250)
            search = null
            refreshNodes()
        }
    }

    fun clearSearch() {
        onQueryChange("")
        refreshNodes()
    }

    /** Refreshes the latest text, starting at page one and keeping same-query rows visible. */
    fun refreshNodes() {
        if (currentSession() == null || _state.value.invalid) return
        search?.cancel()
        search = null
        generation++
        more?.cancel()
        val s = _state.value
        val keep = content.value is Loaded.Ready && !s.pending && s.query.trim() == s.appliedQuery
        _state.update { it.copy(appliedQuery = s.query.trim(), pending = !keep, refreshing = keep,
            loadingMore = false, moreError = null) }
        refresh()
    }

    /** Appends one server page, retaining its ordering and suppressing duplicates across pages. */
    fun loadMore() {
        val open = currentSession() ?: return
        val pages = (content.value as? Loaded.Ready)?.value ?: return
        val s = _state.value
        if (!pages.enabled || !pages.more || s.pending || s.invalid || s.refreshing || s.loadingMore) return
        val current = generation
        val nextPage = pages.page + 1
        _state.update { it.copy(loadingMore = true, moreError = null) }
        more = viewModelScope.launch {
            try {
                val result = session.withClient { admin ->
                    if (currentSession() !== open || current != generation) throw CancellationException()
                    admin.listFederatedNodes(s.appliedQuery.takeIf { it.isNotEmpty() }?.let(Id::of), nextPage, FEDERATION_PAGE).await()
                }
                if (currentSession() !== open || current != generation) return@launch
                if (nextPage > result.totalPages()) {
                    more = null
                    refreshNodes()
                    return@launch
                }
                changeContent { loaded ->
                    val known = loaded.nodes.map { it.id }.toSet()
                    NodePages(true, loaded.nodes + result.items().map { it.toView() }.filter { it.id !in known },
                        result.page(), result.totalPages(), result.totalItems())
                }
                _state.update { it.copy(loadingMore = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentSession() === open && current == generation) {
                    if (e.isNotEnabled()) changeContent { NodePages(enabled = false) }
                    _state.update { it.copy(loadingMore = false, moreError = if (e.isNotEnabled()) null else failure(e)) }
                }
            }
        }
    }

    private fun invalidateRequests() {
        generation++
        invalidateLoad()
        search?.cancel()
        search = null
        more?.cancel()
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

