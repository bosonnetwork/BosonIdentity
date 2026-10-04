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
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.DirectorGuest
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.vertx.core.Vertx
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app's connections to Boson nodes, through their Directors.
 *
 * - [guest]: no identity; node information and registration options.
 * - [deviceClient]: acts as an identity through this app's device key, for routine calls. It is kept
 *   while the app is unlocked and closed by [lock].
 * - [asUser]: acts as the identity itself, with the user key, for one act of authority; the client
 *   is closed and the key destroyed when the block returns.
 *
 * Every client is bound to the node's id ([NodeAddress.nodeId]): tokens it issues are valid only
 * there, and a self-signed Director certificate is accepted only if it is pinned to that id.
 */
class NodeConnections(private val vault: IdentityVault) {

    private val vertx: Vertx by lazy { newVertx() }
    private val mutex = Mutex()
    private val guests = mutableMapOf<NodeAddress, DirectorGuest>()
    private val deviceClients = mutableMapOf<Pair<String, NodeAddress>, DirectorClient>()

    /** The client that needs no identity, for [node]. */
    suspend fun guest(node: NodeAddress): DirectorGuest = mutex.withLock {
        guests.getOrPut(node) {
            DirectorGuest.builder().vertx(vertx).directorUrl(node.url).apply { node.nodeId?.let { nodeId(Id.of(it)) } }.build()
        }
    }

    /**
     * Asks the node for its id, and returns [node] with it. A node first met by its address is
     * trusted on first use from then on; one met by its QR code already carries the id, and must
     * answer with that same one.
     *
     * @throws AppError.Conflict if the node reports another id than the one [node] carries
     */
    suspend fun identify(node: NodeAddress): NodeAddress {
        val reported = call { guest(node).nodeId.await() }.toString()
        if (node.nodeId != null && node.nodeId != reported) throw AppError.Conflict("The node reports a different id")
        return node.copy(nodeId = reported)
    }

    /** This app's device client for [identity] at its home node. */
    suspend fun deviceClient(identity: IdentityInfo, prompt: AuthPrompt): DirectorClient {
        val node = identity.homeNode ?: throw AppError.InvalidInput("The identity has no home node yet")
        val key = identity.userId to node
        mutex.withLock { deviceClients[key]?.let { return it } }
        val deviceKey = vault.deviceKey(identity.userId, prompt)
        val client = builder(node).userId(Id.of(identity.userId)).deviceKey(deviceKey).build()
        mutex.withLock {
            deviceClients[key]?.let {
                client.close()
                return it
            }
            deviceClients[key] = client
        }
        return client
    }

    /**
     * Runs [block] with a client acting as the identity [userId] at [node], with its user key, after
     * the user confirmed with [prompt]. With [withDeviceKey], the client also carries this app's
     * device key, which the block gets too (registering this device needs it); otherwise the block
     * gets null.
     */
    suspend fun <T> asUser(
        userId: String,
        node: NodeAddress,
        prompt: AuthPrompt,
        withDeviceKey: Boolean = false,
        block: suspend (client: DirectorClient, deviceKey: Signature.KeyPair?) -> T,
    ): T = vault.withUserKey(userId, prompt) { userKey ->
        val deviceKey = if (withDeviceKey) vault.deviceKey(userId, prompt) else null
        val client = builder(node).userKey(userKey).apply { deviceKey?.let { deviceKey(it) } }.build()
        try {
            block(client, deviceKey)
        } finally {
            client.close()
        }
    }

    /** Closes the device clients, so the device keys they hold are dropped; the next call unlocks again. */
    suspend fun lock() {
        val closing = mutex.withLock { deviceClients.values.toList().also { deviceClients.clear() } }
        closing.forEach { it.close() }
    }

    /** Closes the device clients of [userId], when the identity leaves this phone. */
    suspend fun forget(userId: String) {
        val closing = mutex.withLock {
            deviceClients.keys.filter { it.first == userId }.mapNotNull { deviceClients.remove(it) }
        }
        closing.forEach { it.close() }
    }

    private fun builder(node: NodeAddress): DirectorClient.Builder =
        DirectorClient.builder().vertx(vertx).directorUrl(node.url).apply { node.nodeId?.let { nodeId(Id.of(it)) } }

    private companion object {
        /**
         * Vert.x with Netty's DNS resolver off, so names resolve through Android's system resolver.
         * Netty's own can't find the phone's name servers and fails on many networks.
         */
        fun newVertx(): Vertx {
            System.setProperty("vertx.disableDnsResolver", "true")
            return Vertx.vertx()
        }
    }
}

/** Runs a Director call, turning its failure into an [AppError]. */
suspend fun <T> call(block: suspend () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw e.toAppError()
    }
