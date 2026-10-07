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

package io.bosonnetwork.identity.core.boson

import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.DirectorAdmin
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An open admin session.
 *
 * @property userId the administrator, the identity that opened it
 * @property node the node it administers
 * @property expiresAt when it ends by itself, in epoch milliseconds
 */
data class AdminState(val userId: String, val node: NodeAddress, val expiresAt: Long)

/**
 * Admin mode: administering a node for a few minutes without keeping the key that allows it.
 *
 * [start] opens the user key once, with the system prompt, and issues with it an admin access token
 * for that one node, valid for [LIFETIME]. The key is destroyed as soon as the token is made; only the
 * token stays, inside an admin client, in memory. When the token runs out (a timer, and also any call
 * the node refuses as unauthorized), the session ends and the client is closed: the next admin action
 * asks for the key again. There is never a long-lived admin credential on the phone.
 *
 * One session at a time: starting another ends the one open.
 *
 * @param scope runs the timer that ends the session
 * @param clock the time, in epoch milliseconds
 * @param issue issues the token with the user key; the Director client's own by default
 */
class AdminSession(
    private val vault: IdentityVault,
    private val nodes: NodeConnections,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val issue: (Signature.KeyPair, Id, Duration) -> String = DirectorAdmin::issueAccessToken,
) {
    private class Open(val state: AdminState, val client: DirectorAdmin, val timer: Job)

    private val mutex = Mutex()
    private var open: Open? = null
    private val _state = MutableStateFlow<AdminState?>(null)

    /** The session open now, or null when there is none. */
    val state: StateFlow<AdminState?> = _state.asStateFlow()

    /**
     * Opens admin mode for [identity] at [node], after the user confirmed with [prompt]. The node is
     * asked once with the new token, so an identity that doesn't administer it learns so at once.
     *
     * @throws AppError.Unauthorized or [AppError.Forbidden] if the identity doesn't administer the node
     * @throws AppError.AuthenticationCancelled if the user dismissed the prompt
     */
    suspend fun start(identity: IdentityInfo, node: NodeAddress, prompt: AuthPrompt): AdminState {
        end()
        // The token is bound to the node's id; a node known only by its address is asked for it.
        val pinned = if (node.nodeId != null) node else nodes.identify(node)
        val nodeId = Id.of(requireNotNull(pinned.nodeId))
        val issuedAt = clock()
        val token = vault.withUserKey(identity.userId, prompt) { key -> issue(key, nodeId, LIFETIME) }
        val client = nodes.admin(pinned, identity.userId, token)
        try {
            call { client.nodeStatus.await() }
        } catch (e: Exception) {
            client.close()
            throw e
        }
        // Counted from before the token was made, so the session never outlives it.
        val state = AdminState(identity.userId, node, issuedAt + LIFETIME.toMillis())
        val previous = mutex.withLock {
            val previous = open
            val timer = scope.launch {
                delay((state.expiresAt - clock()).coerceAtLeast(0))
                end(state)
            }
            open = Open(state, client, timer)
            _state.value = state
            previous
        }
        previous?.let { close(it) }
        return state
    }

    /**
     * Runs [block] with the admin client, its failure turned into an [AppError]. A call the node
     * refuses as unauthorized means the token is no longer good, and ends the session.
     *
     * @throws AppError.Unauthorized if no session is open, or it has ended
     */
    suspend fun <T> withClient(block: suspend (DirectorAdmin) -> T): T {
        val current = mutex.withLock { open } ?: throw AppError.Unauthorized("Admin mode has ended")
        if (clock() >= current.state.expiresAt) {
            end(current.state)
            throw AppError.Unauthorized("Admin mode has ended")
        }
        return try {
            call { block(current.client) }
        } catch (e: AppError.Unauthorized) {
            end(current.state)
            throw e
        }
    }

    /**
     * Ends the session when its time is up. The timer may run late after the phone slept; the
     * countdown on screen checks with this.
     */
    suspend fun endIfExpired() {
        val current = mutex.withLock { open } ?: return
        if (clock() >= current.state.expiresAt) end(current.state)
    }

    /** Ends the session open now, if any ("End now"). */
    suspend fun end() {
        val current = mutex.withLock { open } ?: return
        end(current.state)
    }

    /**
     * Ends the session open now without waiting for it to close: for a caller that can't wait, such as
     * the admin page going away (leaving the page ends admin mode).
     */
    fun endSoon() {
        scope.launch { end() }
    }

    // Ends the session that was [state], unless another one replaced it meanwhile.
    private suspend fun end(state: AdminState) {
        val ended = mutex.withLock {
            val current = open?.takeIf { it.state === state } ?: return
            open = null
            _state.value = null
            current
        }
        close(ended)
    }

    private fun close(ended: Open) {
        ended.client.close()
        // Last: this may be the timer's own coroutine.
        ended.timer.cancel()
    }

    companion object {
        /** How long an admin session lasts. */
        val LIFETIME: Duration = Duration.ofMinutes(10)
    }
}

/**
 * Which identities the app shows the Dashboard to: those that proved they administer their home node
 * ([IdentityInfo.administers]), and those whose account there is marked as an administrator. The
 * second comes from the profile the app reads anyway, reported here, so knowing it costs no prompt.
 *
 * Only the home node for now; nodes an identity administers without being a member there can be added
 * later.
 */
class AdminAccess {
    private val _profileAdmins = MutableStateFlow<Set<String>>(emptySet())

    /** The identities whose home-node account says they are administrators. */
    val profileAdmins: StateFlow<Set<String>> = _profileAdmins.asStateFlow()

    /** Records what the home node's profile of [userId] says. */
    fun reportProfile(userId: String, admin: Boolean) {
        val current = _profileAdmins.value
        val updated = if (admin) current + userId else current - userId
        if (updated != current) _profileAdmins.value = updated
    }

    companion object {
        /** Whether [identity] gets the Dashboard, given [profileAdmins]. */
        fun shows(identity: IdentityInfo?, profileAdmins: Set<String>): Boolean =
            identity != null && (identity.administers || identity.userId in profileAdmins)
    }
}
