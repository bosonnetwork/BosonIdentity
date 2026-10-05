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
import io.bosonnetwork.director.client.NodeStatus
import io.bosonnetwork.director.client.exceptions.DirectorException
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.core.vault.KeyProtector
import io.bosonnetwork.identity.core.vault.Protection
import io.bosonnetwork.identity.core.vault.Sealed
import io.bosonnetwork.identity.core.vault.VaultStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Admin mode: the user key is opened once to issue the token and never kept, and the session ends
 * when its time runs out or the node stops taking the token. Over a real vault (with a stand-in for
 * the Keystore that counts what it opens) and a mocked node.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminSessionTest {

    private class CountingKeystore : KeyProtector {
        val opened = mutableListOf<Protection>()
        override fun isAvailable() = true
        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt) =
            Sealed(ByteArray(12), plaintext.copyOf())
        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt): ByteArray {
            opened += protection
            return sealed.data.copyOf()
        }
        override fun delete(alias: String) = Unit
    }

    private val dir: File = Files.createTempDirectory("admin").toFile()
    private val keystore = CountingKeystore()
    private val vault = IdentityVault(VaultStore(File(dir, "vault")), keystore)
    private val nodes = mockk<NodeConnections>()
    private val client = mockk<DirectorAdmin>()
    private val node = NodeAddress("https://node.example", Id.random().toString())
    private val prompt = AuthPrompt("Admin mode")

    // The keys the issuer was given, and how they were used.
    private val issuedWith = mutableListOf<Signature.KeyPair>()
    private val audiences = mutableListOf<Id>()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun TestScope.session() = AdminSession(
        vault,
        nodes,
        backgroundScope,
        clock = { testScheduler.currentTime },
        issue = { key, nodeId, lifetime ->
            issuedWith += key
            audiences += nodeId
            assertEquals(Duration.ofMinutes(10), lifetime)
            // The real issuer, so the key must be usable right here.
            DirectorAdmin.issueAccessToken(key, nodeId, lifetime)
        },
    )

    private suspend fun identity(): IdentityInfo {
        val info = vault.add(Signature.KeyPair.random(), null, backupVerified = true, keepUserKey = true, prompt = prompt)
        keystore.opened.clear()
        return vault.update(info.userId) { it.copy(homeNode = node) }
    }

    private fun answers(status: Int? = null) {
        every { nodes.admin(any(), any(), any()) } returns client
        every { client.close() } returns CompletableFuture.completedFuture(null)
        every { client.nodeStatus } returns
            if (status == null) CompletableFuture.completedFuture(mockk<NodeStatus>(relaxed = true))
            else CompletableFuture.failedFuture(DirectorException.fromResponse(status, "refused", null))
    }

    @Test
    fun `starting opens the user key once, issues a token for the node, and keeps no key`() = runTest {
        val identity = identity()
        answers()
        val session = session()

        val state = session.start(identity, node, prompt)
        assertEquals(AdminState(identity.userId, node, 10 * 60 * 1000L), state)
        assertEquals(state, session.state.value)

        // One prompt, for the user key; the device key is not involved.
        assertEquals(listOf(Protection.PER_USE), keystore.opened)
        assertEquals(1, issuedWith.size)
        assertEquals(Id.of(node.nodeId!!), audiences.single())
        // The key is destroyed as soon as the token is made; only the token reaches the client.
        assertTrue(issuedWith.single().isDestroyed)
        verify { nodes.admin(node, identity.userId, match { it.isNotEmpty() }) }

        // Calls run with the client, without opening the key again.
        session.withClient { it.nodeStatus }
        assertEquals(1, keystore.opened.size)
    }

    @Test
    fun `an identity that doesn't administer the node gets no session`() = runTest {
        val identity = identity()
        answers(status = 403)
        val session = session()
        try {
            session.start(identity, node, prompt)
            fail("started")
        } catch (e: AppError.Forbidden) {
            // Expected.
        }
        assertNull(session.state.value)
        verify { client.close() }
        assertTrue(issuedWith.single().isDestroyed)
    }

    @Test
    fun `the session ends by itself after ten minutes`() = runTest {
        val identity = identity()
        answers()
        val session = session()
        session.start(identity, node, prompt)

        advanceTimeBy(10 * 60 * 1000L - 1)
        runCurrent()
        assertTrue(session.state.value != null)

        advanceTimeBy(2)
        runCurrent()
        assertNull(session.state.value)
        verify { client.close() }
        try {
            session.withClient { it.nodeStatus }
            fail("still open")
        } catch (e: AppError.Unauthorized) {
            // Expected: admin mode has to be opened again.
        }
    }

    @Test
    fun `a call the node refuses as unauthorized ends the session`() = runTest {
        val identity = identity()
        answers()
        every { client.listPlans() } returns CompletableFuture.failedFuture(DirectorException.fromResponse(401, "expired", null))
        val session = session()
        session.start(identity, node, prompt)

        try {
            session.withClient { it.listPlans().get() }
            fail("accepted")
        } catch (e: AppError.Unauthorized) {
            // Expected.
        }
        assertNull(session.state.value)
        verify { client.close() }
    }

    @Test
    fun `other failures keep the session`() = runTest {
        val identity = identity()
        answers()
        every { client.listPlans() } returns CompletableFuture.failedFuture(DirectorException.fromResponse(404, "gone", null))
        val session = session()
        session.start(identity, node, prompt)
        try {
            session.withClient { it.listPlans().get() }
            fail("found")
        } catch (e: AppError.NotFound) {
            // Expected.
        }
        assertTrue(session.state.value != null)
    }

    @Test
    fun `ending now closes the client, and a dismissed prompt starts nothing`() = runTest {
        val identity = identity()
        answers()
        val session = session()
        session.start(identity, node, prompt)
        session.end()
        assertNull(session.state.value)
        verify { client.close() }

        val dismissing = IdentityVault(VaultStore(File(dir, "vault")), object : KeyProtector by keystore {
            override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt): ByteArray =
                throw AppError.AuthenticationCancelled("dismissed")
        })
        val other = AdminSession(dismissing, nodes, backgroundScope, clock = { testScheduler.currentTime })
        try {
            other.start(identity, node, prompt)
            fail("started")
        } catch (e: AppError.AuthenticationCancelled) {
            // Expected.
        }
        assertNull(other.state.value)
        verify(exactly = 1) { nodes.admin(any(), any(), any()) }
    }

    @Test
    fun `a node known only by its address is asked for its id first`() = runTest {
        val identity = identity()
        answers()
        val unpinned = NodeAddress("https://node.example")
        coEvery { nodes.identify(unpinned) } returns node
        session().start(identity, unpinned, prompt)
        assertEquals(Id.of(node.nodeId!!), audiences.single())
        coVerify { nodes.identify(unpinned) }
        verify { nodes.admin(node, identity.userId, any()) }
    }

    @Test
    fun `the Admin tab shows for an identity that administers, or whose profile says admin`() {
        val access = AdminAccess()
        val plain = IdentityInfo("user")
        assertFalse(AdminAccess.shows(null, access.profileAdmins.value))
        assertFalse(AdminAccess.shows(plain, access.profileAdmins.value))
        assertTrue(AdminAccess.shows(plain.copy(administers = true), access.profileAdmins.value))

        access.reportProfile("user", admin = true)
        assertTrue(AdminAccess.shows(plain, access.profileAdmins.value))
        access.reportProfile("user", admin = false)
        assertFalse(AdminAccess.shows(plain, access.profileAdmins.value))
    }
}
