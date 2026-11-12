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
import io.bosonnetwork.director.client.FederatedHost
import io.bosonnetwork.director.client.FederatedNodeHealth
import io.bosonnetwork.identity.core.boson.AdminSession
import io.bosonnetwork.web.PaginatedResult
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

/** In-memory observations, with totals and ordering supplied by the Director. */
data class ObservationPages<T>(
    val enabled: Boolean,
    val items: List<T> = emptyList(),
    val page: Long = 1,
    val totalPages: Long = 0,
    val total: Long = 0,
) {
    val more: Boolean get() = page < totalPages
}

data class ObservationListState(
    val query: String = "",
    val appliedQuery: String = "",
    val invalid: Boolean = false,
    val pending: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val moreError: String? = null,
)

/** Paging/search shared by the two read-only observation APIs; each view owns its own instance. */
abstract class FederationObservationViewModel<T : Any>(session: AdminSession, context: Context) :
    AdminSectionViewModel<ObservationPages<T>>(session, context) {
    private val _state = MutableStateFlow(ObservationListState())
    val state: StateFlow<ObservationListState> = _state.asStateFlow()
    private var searchJob: Job? = null
    private var moreJob: Job? = null
    private var generation = 0L

    protected abstract suspend fun fetch(admin: DirectorAdmin, query: String, page: Long): PaginatedResult<T>
    protected abstract fun key(item: T): String?
    protected open fun valid(query: String): Boolean = true

    final override suspend fun load(admin: DirectorAdmin): ObservationPages<T> {
        val current = generation
        val open = currentSession()
        return try {
            val result = fetch(admin, _state.value.appliedQuery, 1)
            ObservationPages(true, result.items(), result.page(), result.totalPages(), result.totalItems())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isNotEnabled()) ObservationPages(enabled = false) else throw e
        } finally {
            if (current == generation && currentSession() === open)
                _state.update { it.copy(pending = false, refreshing = false) }
        }
    }

    final override fun onSessionEnded() {
        invalidateRequests()
        _state.value = ObservationListState()
    }

    fun onQueryChange(value: String) {
        if (currentSession() == null || value == _state.value.query) return
        invalidateRequests()
        val invalid = !valid(value.trim())
        _state.update { it.copy(query = value, invalid = invalid, pending = !invalid,
            refreshing = false, loadingMore = false, moreError = null) }
        if (!invalid) searchJob = viewModelScope.launch {
            delay(250)
            searchJob = null
            refreshList()
        }
    }

    fun clearSearch() {
        onQueryChange("")
        refreshList()
    }

    fun refreshList() {
        if (currentSession() == null || _state.value.invalid) return
        searchJob?.cancel()
        searchJob = null
        generation++
        moreJob?.cancel()
        val state = _state.value
        val keep = content.value is Loaded.Ready && !state.pending && state.query.trim() == state.appliedQuery
        _state.update { it.copy(appliedQuery = state.query.trim(), pending = !keep, refreshing = keep,
            loadingMore = false, moreError = null) }
        refresh()
    }

    fun loadMore() {
        val open = currentSession() ?: return
        val pages = (content.value as? Loaded.Ready)?.value ?: return
        val state = _state.value
        if (!pages.enabled || !pages.more || state.invalid || state.pending || state.refreshing || state.loadingMore) return
        val current = generation
        val next = pages.page + 1
        _state.update { it.copy(loadingMore = true, moreError = null) }
        moreJob = viewModelScope.launch {
            try {
                val result = session.withClient { admin ->
                    if (currentSession() !== open || current != generation) throw CancellationException()
                    fetch(admin, state.appliedQuery, next)
                }
                if (currentSession() !== open || current != generation) return@launch
                if (next > result.totalPages()) {
                    moreJob = null
                    refreshList()
                    return@launch
                }
                changeContent { loaded ->
                    val known = loaded.items.map(::key).toSet()
                    ObservationPages(true, loaded.items + result.items().filter { key(it) !in known },
                        result.page(), result.totalPages(), result.totalItems())
                }
                _state.update { it.copy(loadingMore = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentSession() === open && current == generation) {
                    if (e.isNotEnabled()) changeContent { ObservationPages(enabled = false) }
                    _state.update { it.copy(loadingMore = false, moreError = if (e.isNotEnabled()) null else failure(e)) }
                }
            }
        }
    }

    private fun invalidateRequests() {
        generation++
        invalidateLoad()
        searchJob?.cancel()
        searchJob = null
        moreJob?.cancel()
    }
}

@HiltViewModel
class FederationActivityViewModel @Inject constructor(session: AdminSession, @ApplicationContext context: Context) :
    FederationObservationViewModel<FederatedNodeHealth>(session, context) {
    init { watchSession() }

    override fun valid(query: String): Boolean = query.isEmpty() || runCatching { Id.of(query) }.isSuccess
    override fun key(item: FederatedNodeHealth): String = item.nodeId.toString()
    override suspend fun fetch(admin: DirectorAdmin, query: String, page: Long): PaginatedResult<FederatedNodeHealth> =
        admin.listFederatedNodeHealth(query.takeIf { it.isNotEmpty() }?.let(Id::of), page, FEDERATION_PAGE).await()
}

// The API matches unresolved observations using this canonical text, independently of UI language.
const val UNRESOLVED_HOST_QUERY = "Unresolved host"

@HiltViewModel
class FederationHostsViewModel @Inject constructor(session: AdminSession, @ApplicationContext context: Context) :
    FederationObservationViewModel<FederatedHost>(session, context) {
    init { watchSession() }

    override fun key(item: FederatedHost): String? = item.host.orElse(null)
    override suspend fun fetch(admin: DirectorAdmin, query: String, page: Long): PaginatedResult<FederatedHost> =
        admin.listFederatedHosts(query.takeIf { it.isNotEmpty() }, page, FEDERATION_PAGE).await()

    fun showUnresolved() {
        onQueryChange(UNRESOLVED_HOST_QUERY)
        refreshList()
    }
}
