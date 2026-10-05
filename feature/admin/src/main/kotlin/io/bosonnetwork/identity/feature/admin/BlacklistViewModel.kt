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
import io.bosonnetwork.director.client.BlacklistedNode
import io.bosonnetwork.director.client.DirectorAdmin
import io.bosonnetwork.identity.core.boson.AdminSession
import io.bosonnetwork.identity.core.model.AppError
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

/** One blacklist entry: a node by its id, or a host by its name or address. */
data class BlacklistEntry(
    val id: Long,
    val nodeId: String?,
    val host: String?,
    /** Added by the node itself (misbehaviour it detected), not by an administrator. */
    val auto: Boolean,
    val reason: String?,
    val createdAt: Long,
) {
    /** What the entry blocks, as shown and typed to confirm. */
    val target: String get() = nodeId ?: host.orEmpty()
}

/** What an entry blocks. */
enum class BlacklistKind { NODE, HOST }

/**
 * Adding an entry, as typed. Once the form is filled, [confirming] asks to type the first characters
 * of the target again: blacklisting is never one tap.
 */
data class BlacklistForm(
    val kind: BlacklistKind = BlacklistKind.NODE,
    val target: String = "",
    val reason: String = "",
    val confirming: Boolean = false,
) {
    /** The node id, when [kind] is a node and [target] is one. */
    val nodeId: Id? get() = if (kind == BlacklistKind.NODE) runCatching { Id.of(target.trim()) }.getOrNull() else null

    /** Whether the target is something the node can blacklist. */
    val valid: Boolean
        get() = when (kind) {
            BlacklistKind.NODE -> nodeId != null
            BlacklistKind.HOST -> target.trim().let { it.isNotEmpty() && it.none(Char::isWhitespace) && !it.contains("/") }
        }

    val code: String get() = confirmCode(target.trim())
}

data class BlacklistUiState(
    /** Set while the add dialog is open. */
    val add: BlacklistForm? = null,
    /** Set while removing an entry waits for confirmation. */
    val remove: BlacklistEntry? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Blacklist: the nodes and hosts this node refuses; adding one, with a reason, and removing one. */
@HiltViewModel
class BlacklistViewModel @Inject constructor(
    session: AdminSession,
    @ApplicationContext context: Context,
) : AdminSectionViewModel<List<BlacklistEntry>>(session, context) {

    private val _state = MutableStateFlow(BlacklistUiState())
    val state: StateFlow<BlacklistUiState> = _state.asStateFlow()

    init {
        watchSession()
    }

    override suspend fun load(admin: DirectorAdmin): List<BlacklistEntry> =
        admin.listBlacklistedNodes().await().items().map { it.toView() }.sortedByDescending { it.createdAt }

    override fun onSessionEnded() {
        _state.value = BlacklistUiState()
    }

    fun startAdd() = _state.update { it.copy(add = BlacklistForm(), error = null) }
    fun onKindChange(kind: BlacklistKind) = updateForm { it.copy(kind = kind, confirming = false) }
    fun onTargetChange(value: String) = updateForm { it.copy(target = value, confirming = false) }
    fun onReasonChange(value: String) = updateForm { it.copy(reason = value) }

    /**
     * The first tap asks to type the target's first characters; with [typed] matching, the entry is
     * added.
     */
    fun add(typed: String = "") {
        val s = _state.value
        val form = s.add ?: return
        if (s.busy || !form.valid) return
        if (!form.confirming) {
            updateForm { it.copy(confirming = true) }
            return
        }
        if (!confirms(typed, form.code)) return
        val reason = form.reason.trim().ifEmpty { null }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val added = session.withClient { admin ->
                    when (form.kind) {
                        BlacklistKind.NODE -> admin.addBlacklistedNode(requireNotNull(form.nodeId), reason).await()
                        BlacklistKind.HOST -> admin.addBlacklistedHost(form.target.trim(), reason).await()
                    }
                }.toView()
                changeContent { entries -> listOf(added) + entries.filter { it.id != added.id } }
                _state.update { it.copy(busy = false, add = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Conflict) {
                _state.update { it.copy(busy = false, error = context.getString(R.string.adm_bl_already)) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = failure(e)) }
            }
        }
    }

    fun askRemove(entry: BlacklistEntry) = _state.update { it.copy(remove = entry, error = null) }

    fun cancel() = _state.update { if (it.busy) it else it.copy(add = null, remove = null, error = null) }

    /** Removes the entry asked about: the node or host is no longer refused. */
    fun remove() {
        val s = _state.value
        val entry = s.remove ?: return
        if (s.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                session.withClient { it.removeBlacklistedNode(entry.id).await() }
                changeContent { entries -> entries.filter { it.id != entry.id } }
                _state.update { it.copy(busy = false, remove = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.NotFound) {
                changeContent { entries -> entries.filter { it.id != entry.id } }
                _state.update { it.copy(busy = false, remove = null) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = failure(e)) }
            }
        }
    }

    private fun updateForm(transform: (BlacklistForm) -> BlacklistForm) =
        _state.update { s -> s.add?.let { s.copy(add = transform(it), error = null) } ?: s }
}

private fun BlacklistedNode.toView() = BlacklistEntry(
    id = id,
    nodeId = nodeId.orElse(null)?.toString(),
    host = nodeHost.orElse(null)?.takeIf { it.isNotBlank() },
    auto = isAuto,
    reason = reason.orElse(null)?.takeIf { it.isNotBlank() },
    createdAt = createdAt,
)
