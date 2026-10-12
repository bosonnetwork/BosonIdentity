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
import io.bosonnetwork.crypto.CryptoIdentity
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.identifier.Card
import io.bosonnetwork.identifier.Registry
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
    /** The profile revision the card is made from; null when not known. */
    val revision: Long? = null,
) {
    companion object {
        /**
         * The card for [identity]'s profile as this phone keeps it, at its home node with [nodeId] and
         * [messagingHomePeer]. The avatar is named by the address the home node serves it at.
         */
        fun of(identity: IdentityInfo, nodeId: String, messagingHomePeer: String?): CardContent {
            val fields = identity.profile.fields
            return CardContent(
                name = fields.name,
                bio = fields.bio,
                avatar = fields.avatar?.let { avatarUri(nodeId, identity.userId) },
                homeNode = nodeId,
                messagingHomePeer = messagingHomePeer,
                revision = identity.profile.revision,
            )
        }

        /** Where the home node [nodeId] serves [userId]'s avatar, as it names it in the profile. */
        fun avatarUri(nodeId: String, userId: String): String = "bnr://$nodeId/api/v1/client/avatar/$userId"

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

    /**
     * Published, at [version]; the next publication takes the one after it. [content] is what the card
     * says, null when it can't be read.
     */
    data class Published(val version: Int, val content: CardContent? = null) : CardStatus
}

/**
 * The identity's public profile card: its profile as a Boson DID Card, so that anyone can look up the
 * name, avatar and home node by the id.
 *
 * The phone runs no DHT node, so the card goes through the home node's web gateway, which stores it
 * persistently and announces it again and again, keeping it in the DHT while the phone is off. The
 * card and the DHT value holding it are signed by the user key; the gateway only carries them, acting
 * for the identity through this app's device key. Boson's identity registry does the publishing over
 * the gateway (a [io.bosonnetwork.Node]; made without a resolution cache, as it only publishes),
 * choosing the version as every Boson app does.
 */
class ProfileCards(private val vault: IdentityVault, private val nodes: NodeConnections) {

    /** Whether the gateway keeps the identity's card. */
    suspend fun status(identity: IdentityInfo, prompt: AuthPrompt): CardStatus =
        nodes.gateway(identity, prompt) { gateway ->
            val published = Registry.DHTRegistry(gateway, 0).getPublished(Id.of(identity.userId)).await().orElse(null)
            if (published == null) CardStatus.NotPublished
            else CardStatus.Published(published.version, published.card.orElse(null)?.let(::read))
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
            // The registry publishes past every version known (the gateway's, the DHT's); this phone adds
            // the last one it published, which a card that expired everywhere may still be remembered at.
            val kept = vault.identities.value.firstOrNull { it.userId == identity.userId }?.cardVersion
            val last = listOfNotNull(kept, identity.cardVersion).maxOrNull() ?: -1
            val owner = CryptoIdentity(userKey)
            val card = UserProfile.builder(owner)
                .name(content.name)
                .bio(content.bio)
                .avatar(content.avatar)
                .homeNode(Id.of(content.homeNode))
                .messagingHomePeer(content.messagingHomePeer?.let { Id.of(it) })
                .build()
                .card
            val version = Registry.DHTRegistry(gateway, 0).publish(owner, card, last).await()
            vault.update(identity.userId) { it.copy(cardVersion = version, cardRevision = content.revision) }
            version
        }

    /**
     * Stops publishing the card: the gateway no longer keeps or announces it. Copies other nodes hold
     * expire on their own.
     */
    suspend fun unpublish(identity: IdentityInfo, prompt: AuthPrompt) {
        nodes.gateway(identity, prompt) { gateway -> Registry.DHTRegistry(gateway, 0).unpublish(Id.of(identity.userId)).await() }
        // The version stays: a later card must still be newer than the one other nodes may remember.
        vault.update(identity.userId) { it.copy(cardRevision = null) }
    }

    // What a published card says; null when it isn't a profile card.
    private fun read(card: Card): CardContent? = try {
        val profile = UserProfile.fromCard(card)
        CardContent(
            name = profile.name.orElse(null),
            bio = profile.bio.orElse(null),
            avatar = profile.avatar.orElse(null),
            homeNode = profile.homeNode.orElse(null)?.toString() ?: "",
            messagingHomePeer = profile.messagingHomePeer.orElse(null)?.toString(),
        )
    } catch (e: Exception) {
        null
    }

    companion object {
        /**
         * Whether a published card says something else than [current] would: its name, bio, avatar, home
         * node or messaging peer differ (the revision does not count). Null [published] (unreadable) is
         * not called out of date.
         */
        fun outdated(published: CardContent?, current: CardContent): Boolean =
            published != null && published.copy(revision = null) != current.copy(revision = null)

    }
}
