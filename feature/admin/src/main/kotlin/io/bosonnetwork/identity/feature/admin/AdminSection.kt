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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.director.client.DirectorAdmin
import io.bosonnetwork.director.client.exceptions.NotEnabledException
import io.bosonnetwork.identity.core.boson.AdminSession
import io.bosonnetwork.identity.core.boson.AdminState
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.model.NodeAddress
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a section of Admin shows: nothing until admin mode is open, then what the node answered. */
sealed interface Loaded<out T> {
    /** No admin session: nothing to show. */
    data object Idle : Loaded<Nothing>
    data object Loading : Loaded<Nothing>
    data class Ready<out T>(val value: T) : Loaded<T>
    data class Failed(val message: String) : Loaded<Nothing>
}

/**
 * One section of Admin (Overview, Users, ...). It asks the node through the admin session whenever a
 * session opens, and drops what it showed when the session ends, so nothing administrative stays on
 * screen after admin mode.
 *
 * A subclass calls [watchSession] last in its `init`, once its own state exists.
 */
abstract class AdminSectionViewModel<T>(
    protected val session: AdminSession,
    protected val context: Context,
) : ViewModel() {

    private val _content = MutableStateFlow<Loaded<T>>(Loaded.Idle)

    /** What the node answered, for the session open now. */
    val content: StateFlow<Loaded<T>> = _content.asStateFlow()

    private var loading: Job? = null
    private var shown: AdminState? = null

    /** Loads whenever a session opens, and clears everything when it ends. */
    protected fun watchSession() {
        viewModelScope.launch {
            session.state.collect { state ->
                if (state == null) {
                    loading?.cancel()
                    shown = null
                    _content.value = Loaded.Idle
                    onSessionEnded()
                } else if (state != shown) {
                    shown = state
                    _content.value = Loaded.Idle
                    refresh()
                }
            }
        }
    }

    /** Asks the node again. What is shown stays until the answer comes. */
    fun refresh() {
        if (shown == null) return
        loading?.cancel()
        if (_content.value !is Loaded.Ready) _content.value = Loaded.Loading
        loading = viewModelScope.launch {
            _content.value = try {
                Loaded.Ready(session.withClient { load(it) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A token the node no longer takes ended the session: there is nothing to show.
                if (session.state.value == null) Loaded.Idle else Loaded.Failed(e.userMessage(context))
            }
        }
    }

    /** Reads the section from the node. */
    protected abstract suspend fun load(admin: DirectorAdmin): T

    /** Drops the section's own state (dialogs, selections) when admin mode ends. */
    protected open fun onSessionEnded() {}

    /** Changes what is shown, after an action the node accepted. */
    protected fun changeContent(transform: (T) -> T) {
        val current = _content.value
        if (current is Loaded.Ready) _content.value = Loaded.Ready(transform(current.value))
    }

    /** The message for a failed action: a session that ended says so, anything else as usual. */
    protected fun failure(e: Exception): String =
        if (session.state.value == null) context.getString(R.string.adm_session_ended) else e.userMessage(context)
}

/** What to type to confirm a destructive action on something known by its id: its first four characters. */
fun confirmCode(id: String): String = id.take(4)

/** Whether [typed] confirms an action whose code is [code]. Letters must match exactly: ids are case-sensitive. */
fun confirms(typed: String, code: String): Boolean = code.isNotEmpty() && typed.trim() == code

/** Whether this failure says the node doesn't do this at all (federation turned off). */
internal fun Throwable.isNotEnabled(): Boolean {
    var e: Throwable? = this
    while (e != null) {
        if (e is NotEnabledException) return true
        e = e.cause
    }
    return false
}

/** The node's host name, to name it in a sentence; its URL when that has none. */
internal fun NodeAddress.host(): String = runCatching { URI(url).host }.getOrNull() ?: url
