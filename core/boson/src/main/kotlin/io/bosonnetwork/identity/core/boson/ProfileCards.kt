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
import io.bosonnetwork.UserProfile
import io.bosonnetwork.Value
import io.bosonnetwork.crypto.CryptoIdentity
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.NodeStatus
import io.bosonnetwork.director.client.Profile
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import kotlinx.coroutines.future.await

/**
 * What a published profile card says: the claims of its Boson profile credential. Everything in it
 * is public; anyone who knows the id can read it.
 *
 * @property homeNode the home node's id; the node announces itself under it, so other nodes find
 *   where to fetch the avatar
 * @property avatar the avatar's bnr:// URI, as the home node gives it (the node and the path there that
 *   serves it to anyone), or null when the account has none
 * @property messagingHomePeer the home node's messaging service, when it offers one
 */
data class CardContent(
    val name: String?,
    val bio: String?,
    val avatar: String?,
    val homeNode: String,
    val messagingHomePeer: String?,
) {
    companion object {
        /** The card for [userId]'s [profile] at the node with [nodeId] and [status]. */
        fun of(userId: String, profile: Profile, nodeId: String, status: NodeStatus?): CardContent =
            CardContent(
                name = profile.name.orElse(null)?.takeIf { it.isNotBlank() },
                bio = profile.bio.orElse(null)?.takeIf { it.isNotBlank() },
                avatar = profile.avatar.orElse(null)?.takeIf { it.isNotBlank() },
                homeNode = nodeId,
                messagingHomePeer = status?.getService(NodeStatus.Service.PHOTON_MESSAGING)?.orElse(null)?.peerId?.toString(),
            )
    }
}

/** Whether the identity's profile card is published through its home node. */
sealed interface CardStatus {
    data object NotPublished : CardStatus

    /** Published, at [version]; the next publication takes the one after it. */
    data class Published(val version: Int) : CardStatus
}

/**
 * The identity's public profile card: its profile as a Boson DID Card, so that anyone can look up the
 * name, avatar and home node by the id.
 *
 * The phone runs no DHT node, so the card goes through the home node's web gateway, which stores it
 * persistently and announces it again and again, keeping it in the DHT while the phone is off. The
 * card and the DHT value holding it are signed by the user key; the gateway only carries them, acting
 * for the identity through this app's device key.
 */
class ProfileCards(private val vault: IdentityVault, private val nodes: NodeConnections) {

    /** Whether the gateway keeps the identity's card. */
    suspend fun status(identity: IdentityInfo, prompt: AuthPrompt): CardStatus =
        nodes.gateway(identity, prompt) { gateway ->
            val stored = gateway.getValue(Id.of(identity.userId)).await().orElse(null)
            if (stored == null) CardStatus.NotPublished else CardStatus.Published(stored.sequenceNumber)
        }

    /** Publishes [content], after the user confirmed with [prompt]; returns the version published. */
    suspend fun publish(identity: IdentityInfo, content: CardContent, prompt: AuthPrompt): Int =
        vault.withUserKey(identity.userId, prompt) { userKey -> publish(identity, content, userKey, prompt) }

    /**
     * Publishes [content] signed with [userKey], which the caller already opened (saving the profile
     * and publishing it under one prompt). Returns the version published.
     */
    suspend fun publish(identity: IdentityInfo, content: CardContent, userKey: Signature.KeyPair, prompt: AuthPrompt): Int =
        nodes.gateway(identity, prompt) { gateway ->
            val id = Id.of(identity.userId)
            // The DHT keeps only a newer version than it has, which may have come from elsewhere.
            val stored = gateway.getValue(id).await().orElse(null)?.sequenceNumber
            val found = if (stored == null) gateway.findValue(id).await().orElse(null)?.sequenceNumber else null
            val version = nextVersion(stored ?: found)
            val owner = CryptoIdentity(userKey)
            val card = UserProfile.builder(owner)
                .name(content.name)
                .bio(content.bio)
                .avatar(content.avatar)
                .homeNode(Id.of(content.homeNode))
                .messagingHomePeer(content.messagingHomePeer?.let { Id.of(it) })
                .build()
                .card
            // What a DHT registry stores for a card: the card in a value signed by the same identity,
            // versioned by its sequence number. Registry.DHTRegistry itself is not used: its resolver
            // cache needs Caffeine, which boson-api leaves out of Android clients on purpose.
            val value = Value.signedBuilder()
                .identity(owner)
                .sequenceNumber(version)
                .data(card.toBytes())
                .build()
            // Stored persistently: the gateway announces it again before it expires.
            gateway.storeValue(value, true).await()
            version
        }

    /**
     * Stops publishing the card: the gateway no longer keeps or announces it. Copies other nodes hold
     * expire on their own.
     */
    suspend fun unpublish(identity: IdentityInfo, prompt: AuthPrompt) {
        nodes.gateway(identity, prompt) { gateway -> gateway.removeValue(Id.of(identity.userId)).await() }
    }

    companion object {
        /** The version after [current], the published one (null when none is). */
        fun nextVersion(current: Int?): Int = if (current == null) 0 else current + 1
    }
}
