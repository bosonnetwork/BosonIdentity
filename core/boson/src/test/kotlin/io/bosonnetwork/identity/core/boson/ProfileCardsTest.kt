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
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.higgs.HiggsNode
import io.bosonnetwork.identifier.Card
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.mockk.coEvery
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import io.bosonnetwork.director.client.NodeStatus
import io.bosonnetwork.director.client.Profile
import io.bosonnetwork.identity.core.model.NodeAddress
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileCardsTest {
    private val userId = Id.random().toString()
    private val nodeId = Id.random().toString()

    private fun profile(name: String?, bio: String?, avatar: String?): Profile = mockk {
        every { this@mockk.name } returns Optional.ofNullable(name)
        every { this@mockk.bio } returns Optional.ofNullable(bio)
        every { this@mockk.avatar } returns Optional.ofNullable(avatar)
    }

    @Test
    fun `publishing stores the card signed by the user key, persistently, past every version known`() = runBlocking {
        val userKey = Signature.KeyPair.random()
        val identity = IdentityInfo(Id.of(userKey.publicKey().bytes()).toString(), homeNode = NodeAddress("https://node.example"))
        val stored = slot<Value>()
        val gateway = mockk<HiggsNode> {
            every { getValue(any()) } returns CompletableFuture.completedFuture(
                Optional.of(Value.signedBuilder().key(userKey).sequenceNumber(4).data(byteArrayOf(1)).build()),
            )
            // Another phone published a newer one, which the gateway here doesn't have.
            every { findValue(any<Id>()) } returns CompletableFuture.completedFuture(
                Optional.of(Value.signedBuilder().key(userKey).sequenceNumber(6).data(byteArrayOf(1)).build()),
            )
            every { storeValue(capture(stored), true) } returns CompletableFuture.completedFuture(null)
        }
        val nodes = mockk<NodeConnections> {
            coEvery { gateway<Int>(any(), any(), any()) } coAnswers { arg<suspend (HiggsNode) -> Int>(2).invoke(gateway) }
        }
        val recorded = mutableListOf<IdentityInfo>()
        val vault = mockk<IdentityVault> {
            every { identities } returns MutableStateFlow(listOf(identity))
            coEvery { update(identity.userId, any()) } answers {
                secondArg<(IdentityInfo) -> IdentityInfo>().invoke(identity).also { recorded += it }
            }
        }
        val content = CardContent("Alice", "Hi", "bnr://$nodeId/api/v1/client/avatar/${identity.userId}", nodeId, null, revision = 9)

        val version = ProfileCards(vault, nodes).publish(identity, content, userKey, AuthPrompt("Publish"))

        assertEquals(7, version)
        assertEquals(7, recorded.single().cardVersion)
        assertEquals(9L, recorded.single().cardRevision)
        val value = stored.captured
        assertTrue(value.isValid)
        assertEquals(identity.userId, value.publicKey.toString())
        assertEquals(7, value.sequenceNumber)
        val card = Card.parse(value.data)
        assertTrue(card.isGenuine)
        val profile = UserProfile.fromCard(card)
        assertEquals("Alice", profile.name.orElse(null))
        assertEquals(nodeId, profile.homeNode.orElse(null)?.toString())
        assertEquals(content.avatar, profile.avatar.orElse(null))
    }

    @Test
    fun `a version this phone published is never gone back on, though the network forgot it`() = runBlocking {
        val userKey = Signature.KeyPair.random()
        val identity = IdentityInfo(Id.of(userKey.publicKey().bytes()).toString(), homeNode = NodeAddress("https://node.example"),
            cardVersion = 12)
        val stored = slot<Value>()
        val gateway = mockk<HiggsNode> {
            every { getValue(any()) } returns CompletableFuture.completedFuture(Optional.empty())
            every { findValue(any<Id>()) } returns CompletableFuture.completedFuture(Optional.empty())
            every { storeValue(capture(stored), true) } returns CompletableFuture.completedFuture(null)
        }
        val nodes = mockk<NodeConnections> {
            coEvery { gateway<Int>(any(), any(), any()) } coAnswers { arg<suspend (HiggsNode) -> Int>(2).invoke(gateway) }
        }
        val vault = mockk<IdentityVault> {
            every { identities } returns MutableStateFlow(listOf(identity))
            coEvery { update(identity.userId, any()) } answers { secondArg<(IdentityInfo) -> IdentityInfo>().invoke(identity) }
        }
        val content = CardContent("Alice", null, null, nodeId, null)
        assertEquals(13, ProfileCards(vault, nodes).publish(identity, content, userKey, AuthPrompt("Publish")))
        assertEquals(13, stored.captured.sequenceNumber)
    }

    @Test
    fun `a card is out of date when it says something else than the profile`() {
        val current = CardContent("Alice", "Hi", null, nodeId, null, revision = 4)
        assertFalse(ProfileCards.outdated(current.copy(revision = 2), current))
        assertTrue(ProfileCards.outdated(current.copy(name = "Ann", revision = null), current))
        assertFalse(ProfileCards.outdated(null, current))
    }

    @Test
    fun `the first version is 0, then each one counts up`() {
        assertEquals(0, ProfileCards.nextVersion(null))
        assertEquals(1, ProfileCards.nextVersion(0))
        assertEquals(8, ProfileCards.nextVersion(7))
    }

    @Test
    fun `a card names the home node, and the avatar by the URI the node gives it`() {
        val avatar = "bnr://$nodeId/api/v1/client/avatar/$userId"
        val content = CardContent.of(userId, profile("Alice", "Hi", avatar), nodeId, null)
        assertEquals("Alice", content.name)
        assertEquals("Hi", content.bio)
        assertEquals(avatar, content.avatar)
        assertEquals(nodeId, content.homeNode)
        assertNull(content.messagingHomePeer)
    }

    @Test
    fun `blank fields and a missing avatar are left out`() {
        val content = CardContent.of(userId, profile(" ", null, null), nodeId, null)
        assertNull(content.name)
        assertNull(content.bio)
        assertNull(content.avatar)
    }

    @Test
    fun `a node with messaging puts its messaging peer on the card`() {
        val peer = Id.random()
        val service = mockk<NodeStatus.Service> { every { peerId } returns peer }
        val status = mockk<NodeStatus> {
            every { getService(NodeStatus.Service.PHOTON_MESSAGING) } returns Optional.of(service)
        }
        val content = CardContent.of(userId, profile("Alice", null, null), nodeId, status)
        assertEquals(peer.toString(), content.messagingHomePeer)
    }

    @Test
    fun `a logo path is taken as one at the node, and only http logos are used`() {
        val node = NodeAddress("https://node.example")
        assertEquals("https://node.example/logo.png", NodeLogos.resolve(node, "/logo.png"))
        assertEquals("https://node.example/logo.png", NodeLogos.resolve(node, "logo.png"))
        assertEquals("https://cdn.example/a.png", NodeLogos.resolve(node, "https://cdn.example/a.png"))
        assertNull(NodeLogos.resolve(node, "data:image/png;base64,AAAA"))
        assertNull(NodeLogos.resolve(node, " "))
        assertNull(NodeLogos.resolve(node, null))
    }
}
