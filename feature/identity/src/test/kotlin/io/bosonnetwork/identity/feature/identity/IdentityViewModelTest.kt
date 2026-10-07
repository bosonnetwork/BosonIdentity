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

package io.bosonnetwork.identity.feature.identity

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.NodeStatus
import io.bosonnetwork.director.client.Profile
import io.bosonnetwork.director.client.ProfileUpdate
import io.bosonnetwork.director.client.exceptions.DirectorException
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.AdminAccess
import io.bosonnetwork.identity.core.boson.CardContent
import io.bosonnetwork.identity.core.boson.CardStatus
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.NodeLogos
import io.bosonnetwork.identity.core.boson.ProfileCards
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
import io.mockk.slot
import java.io.File
import java.nio.file.Files
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Identity tab's logic, over a real vault (with a stand-in for the Keystore), a real DataStore for
 * the identity in use, and a mocked node and card publisher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityViewModelTest {

    // Seals nothing: the screen never opens a key itself; the node connections are mocked.
    private class NoKeystore : KeyProtector {
        override fun isAvailable() = true
        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt) =
            Sealed(ByteArray(12), plaintext.copyOf())
        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt) = sealed.data.copyOf()
        override fun delete(alias: String) = Unit
    }

    private val nodeId = Id.random()
    private val node = NodeAddress("https://node.example", nodeId.toString())
    private val dir: File = Files.createTempDirectory("identity").toFile()
    private val vault = IdentityVault(VaultStore(File(dir, "vault")), NoKeystore())
    private val storeJob = Job()
    private val active = ActiveIdentity(
        vault,
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob)) { File(dir, "active.preferences_pb") },
    )
    private val client = mockk<DirectorClient>()
    private val nodes = mockk<NodeConnections>()
    private val cards = mockk<ProfileCards>()
    private val logos = mockk<NodeLogos>()
    private val adminAccess = AdminAccess()
    private val userKey = Signature.KeyPair.random()
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.id_wrong_passphrase) } returns "wrong passphrase"
        every { getString(R.string.id_backup_first) } returns "back up first"
        every { getString(R.string.id_card_update_failed, any()) } answers { "card not updated: ${(args[1] as Array<*>)[0]}" }
        every { getString(io.bosonnetwork.identity.core.designsystem.R.string.ds_error_passphrase_required) } returns "passphrase needed"
        every { getString(io.bosonnetwork.identity.core.designsystem.R.string.ds_error_network) } returns "no network"
    }
    private val viewModels = mutableListOf<IdentityViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { nodes.deviceClient(any(), any()) } returns client
        coEvery { nodes.asUser<Any?>(any(), any(), any(), any(), any()) } coAnswers {
            arg<suspend (DirectorClient, Signature.KeyPair?) -> Any?>(4).invoke(client, null)
        }
        coEvery { nodes.asUserWithKey<Any?>(any(), any(), any(), any()) } coAnswers {
            arg<suspend (DirectorClient, Signature.KeyPair) -> Any?>(3).invoke(client, userKey)
        }
        every { client.profile } returns CompletableFuture.completedFuture(profile(name = "Alice"))
        every { client.nodeStatus } returns CompletableFuture.completedFuture(status(gateway = true))
        coEvery { logos.load(any(), any()) } returns null
        coEvery { cards.status(any(), any()) } returns CardStatus.NotPublished
    }

    @After
    fun tearDown() {
        // A vault write may still be on its way back to the main thread; let it land before the
        // test's main dispatcher goes.
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        storeJob.cancel()
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun viewModel() = IdentityViewModel(vault, active, nodes, cards, logos, adminAccess, context).also { viewModels += it }

    // The profile as the client would decode it; its constructor is the one Jackson uses.
    private fun profile(
        name: String? = null,
        bio: String? = null,
        email: String? = null,
        passphrase: Boolean = false,
        admin: Boolean = false,
    ): Profile =
        Profile::class.java.getDeclaredConstructor(
            Id::class.java, Boolean::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            Long::class.java, Long::class.java, String::class.java, Boolean::class.java,
        ).apply { isAccessible = true }.newInstance(Id.random(), admin, name, null, email, bio, 0L, 0L, "Free", passphrase)

    private fun status(gateway: Boolean, name: String? = "Example node", running: Boolean = true): NodeStatus {
        val service = mockk<NodeStatus.Service>()
        return mockk {
            every { this@mockk.nodeId } returns this@IdentityViewModelTest.nodeId
            every { this@mockk.name } returns Optional.ofNullable(name)
            every { isRunning } returns running
            every { logo } returns Optional.empty()
            every { getService(NodeStatus.Service.WEB_GATEWAY) } returns if (gateway) Optional.of(service) else Optional.empty()
            every { getService(NodeStatus.Service.PHOTON_MESSAGING) } returns Optional.empty()
        }
    }

    private suspend fun addIdentity(homeNode: NodeAddress? = node, backupVerified: Boolean = true): String {
        val userId = vault.add(Signature.KeyPair.random(), null, backupVerified, keepUserKey = true, prompt = AuthPrompt("Confirm")).userId
        if (homeNode != null) vault.update(userId) { it.copy(homeNode = homeNode) }
        return userId
    }

    private suspend fun IdentityViewModel.ready(): NodeState.Ready =
        state.first { it.node is NodeState.Ready }.node as NodeState.Ready

    private suspend fun IdentityViewModel.card(): CardState =
        state.first { it.card.state != CardState.Unknown && it.card.state != CardState.Checking }.card.state

    // ProfileUpdate keeps its fields to itself; the test reads what would be sent.
    private fun ProfileUpdate.sent(): Map<*, *> =
        ProfileUpdate::class.java.getDeclaredMethod("fields").apply { isAccessible = true }.invoke(this) as Map<*, *>

    private fun failed(status: Int, retryAfter: String? = null): CompletableFuture<Void> =
        CompletableFuture.failedFuture(DirectorException.fromResponse(status, "refused", retryAfter))

    // ---- The identity and its node ------------------------------------------------------------

    @Test
    fun `the identity in use is shown with its node, and switching shows the other`() = runTest {
        val first = addIdentity()
        val second = addIdentity()
        val third = addIdentity(homeNode = null)
        val bob = mockk<DirectorClient> {
            every { profile } returns CompletableFuture.completedFuture(profile(name = "Bob"))
            every { nodeStatus } returns CompletableFuture.completedFuture(status(gateway = false))
        }
        coEvery { nodes.deviceClient(match<IdentityInfo> { it.userId == second }, any()) } returns bob

        val vm = viewModel()
        var ready = vm.ready()
        assertEquals(first, vm.state.value.identity?.userId)
        assertEquals("Alice", ready.profile.name)
        assertEquals("Example node", ready.node.name)
        assertEquals(nodeId.toString(), ready.node.nodeId)
        assertTrue(ready.node.running)

        active.select(second)
        ready = vm.state.first { it.identity?.userId == second && it.node is NodeState.Ready }.node as NodeState.Ready
        assertEquals("Bob", ready.profile.name)
        assertEquals("Bob", vault.identities.first { ids -> ids.any { it.name == "Bob" } }.single { it.userId == second }.name)

        active.select(third)
        val state = vm.state.first { it.identity?.userId == third }
        assertEquals(NodeState.None, state.node)
        assertEquals(CardState.Unknown, state.card.state)
    }

    @Test
    fun `without a home node the node is not asked`() = runTest {
        addIdentity(homeNode = null)
        val vm = viewModel()
        assertEquals(NodeState.None, vm.state.first { it.identity != null }.node)
        coVerify(exactly = 0) { nodes.deviceClient(any(), any()) }
    }

    @Test
    fun `a dismissed unlock prompt leaves the node locked`() = runTest {
        addIdentity()
        coEvery { nodes.deviceClient(any(), any()) } throws AppError.AuthenticationCancelled("no")
        val vm = viewModel()
        assertEquals(NodeState.Locked, vm.state.first { it.node == NodeState.Locked }.node)
    }

    @Test
    fun `a node that doesn't answer is unreachable`() = runTest {
        addIdentity()
        every { client.profile } returns CompletableFuture.failedFuture(DirectorException("no answer", java.io.IOException("refused")))
        val failed = viewModel().state.first { it.node is NodeState.Failed }.node as NodeState.Failed
        assertTrue(failed.unreachable)
        assertEquals("no network", failed.message)
    }

    @Test
    fun `the profile shows even when the node's status can't be read`() = runTest {
        addIdentity()
        every { client.nodeStatus } returns CompletableFuture.failedFuture(DirectorException.fromResponse(500, "oops", null))
        val ready = viewModel().ready()
        assertEquals("Alice", ready.profile.name)
        assertNull(ready.node.nodeId)
    }

    @Test
    fun `an account marked as an administrator gets the Dashboard`() = runTest {
        val userId = addIdentity()
        every { client.profile } returns CompletableFuture.completedFuture(profile(name = "Root", admin = true))
        val vm = viewModel()
        vm.ready()
        assertTrue(vm.state.value.administers)
        assertEquals(setOf(userId), adminAccess.profileAdmins.value)
    }

    @Test
    fun `an identity that proved it administers the node gets the Dashboard`() = runTest {
        val userId = addIdentity()
        val vm = viewModel()
        vm.ready()
        assertFalse(vm.state.value.administers)
        vault.update(userId) { it.copy(administers = true) }
        assertTrue(vm.state.first { it.administers }.administers)
    }

    @Test
    fun `the node QR code is what the scanner reads`() {
        val json = JSONObject(nodeQrPayload(node))
        assertEquals(node.url, json.getString("url"))
        assertEquals(node.nodeId, json.getString("id"))
        assertFalse(JSONObject(nodeQrPayload(NodeAddress("https://ca.example"))).has("id"))
        assertEquals("node.example", node.host())
    }

    // ---- The public profile card ---------------------------------------------------------------

    @Test
    fun `the card's state is asked for when the node has a web gateway`() = runTest {
        addIdentity()
        coEvery { cards.status(any(), any()) } returns CardStatus.Published(3)
        assertEquals(CardState.On(3), viewModel().card())
    }

    @Test
    fun `a node without a web gateway can't publish cards`() = runTest {
        addIdentity()
        every { client.nodeStatus } returns CompletableFuture.completedFuture(status(gateway = false))
        assertEquals(CardState.Unavailable, viewModel().card())
        coVerify(exactly = 0) { cards.status(any(), any()) }
    }

    @Test
    fun `publishing is confirmed first, then signs the node's card`() = runTest {
        val userId = addIdentity()
        val content = slot<CardContent>()
        coEvery { cards.publish(any(), capture(content), any<AuthPrompt>()) } returns 0
        val vm = viewModel()
        assertEquals(CardState.Off, vm.card())

        vm.requestCard(true)
        assertEquals(CardConfirm.PUBLISH, vm.state.value.card.confirm)
        coVerify(exactly = 0) { cards.publish(any(), any(), any<AuthPrompt>()) }

        vm.confirmCard()
        assertEquals(CardState.On(0), vm.state.first { it.card.state is CardState.On }.card.state)
        assertEquals("Alice", content.captured.name)
        assertEquals(nodeId.toString(), content.captured.homeNode)
        assertNull(content.captured.avatar)
        coVerify { cards.publish(match { it.userId == userId }, any(), any<AuthPrompt>()) }
    }

    @Test
    fun `publishing needs a checked backup`() = runTest {
        addIdentity(backupVerified = false)
        val vm = viewModel()
        vm.card()
        vm.requestCard(true)
        assertNull(vm.state.value.card.confirm)
        assertEquals("back up first", vm.state.value.card.error)
    }

    @Test
    fun `stopping removes the card from the gateway`() = runTest {
        addIdentity()
        coEvery { cards.status(any(), any()) } returns CardStatus.Published(2)
        coEvery { cards.unpublish(any(), any()) } returns Unit
        val vm = viewModel()
        vm.card()
        vm.requestCard(false)
        assertEquals(CardConfirm.STOP, vm.state.value.card.confirm)
        vm.confirmCard()
        assertEquals(CardState.Off, vm.state.first { it.card.state == CardState.Off }.card.state)
        coVerify { cards.unpublish(any(), any()) }
    }

    @Test
    fun `a cancelled prompt leaves the card as it was`() = runTest {
        addIdentity()
        coEvery { cards.publish(any(), any(), any<AuthPrompt>()) } throws AppError.AuthenticationCancelled("no")
        val vm = viewModel()
        vm.card()
        vm.requestCard(true)
        vm.confirmCard()
        val card = vm.state.first { !it.card.busy }.card
        assertEquals(CardState.Off, card.state)
        assertNull(card.error)
    }

    // ---- Editing the profile -------------------------------------------------------------------

    @Test
    fun `saving sends only the changed fields, with the user key`() = runTest {
        val userId = addIdentity()
        val update = slot<ProfileUpdate>()
        every { client.updateProfile(capture(update), any()) } returns CompletableFuture.completedFuture(null)
        val vm = viewModel()
        vm.ready()

        vm.startEdit()
        vm.onNameChange("  Alicia ")
        vm.onBioChange("Hello")
        vm.save()
        val state = vm.state.first { it.edit == null }
        assertEquals(mapOf("name" to "Alicia", "bio" to "Hello"), update.captured.sent())
        coVerify { nodes.asUser<Any?>(userId, node, any(), false, any()) }
        val view = (state.node as NodeState.Ready).profile
        assertEquals("Alicia", view.name)
        assertEquals("Hello", view.bio)
        assertEquals("Alicia", vault.identities.first { it.single().name == "Alicia" }.single().name)
        // No card published: nothing to sign beyond the call.
        coVerify(exactly = 0) { nodes.asUserWithKey<Any?>(any(), any(), any(), any()) }
    }

    @Test
    fun `a published card is updated with the profile, under the same prompt`() = runTest {
        addIdentity()
        coEvery { cards.status(any(), any()) } returns CardStatus.Published(4)
        every { client.updateProfile(any(), any()) } returns CompletableFuture.completedFuture(null)
        val content = slot<CardContent>()
        coEvery { cards.publish(any(), capture(content), userKey, any()) } returns 5
        val vm = viewModel()
        vm.card()

        vm.startEdit()
        assertTrue(vm.state.value.edit!!.updatesCard)
        vm.onNameChange("Alicia")
        vm.save()
        val state = vm.state.first { it.edit == null }
        assertEquals(CardState.On(5), state.card.state)
        assertEquals("Alicia", content.captured.name)
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the profile is saved even when its card can't be updated`() = runTest {
        addIdentity()
        coEvery { cards.status(any(), any()) } returns CardStatus.Published(4)
        every { client.updateProfile(any(), any()) } returns CompletableFuture.completedFuture(null)
        coEvery { cards.publish(any(), any(), userKey, any()) } throws AppError.Network("down")
        val vm = viewModel()
        vm.card()

        vm.startEdit()
        vm.onBioChange("Hello")
        vm.save()
        val state = vm.state.first { it.edit == null }
        assertEquals("Hello", (state.node as NodeState.Ready).profile.bio)
        assertEquals(CardState.On(4), state.card.state)
        assertEquals("card not updated: no network", state.card.error)
    }

    @Test
    fun `an email change alone doesn't publish the card again`() = runTest {
        addIdentity()
        coEvery { cards.status(any(), any()) } returns CardStatus.Published(4)
        every { client.updateProfile(any(), any()) } returns CompletableFuture.completedFuture(null)
        val vm = viewModel()
        vm.card()
        vm.startEdit()
        vm.onEmailChange("a@example.com")
        vm.save()
        vm.state.first { it.edit == null }
        coVerify(exactly = 0) { cards.publish(any(), any(), any<Signature.KeyPair>(), any()) }
    }

    @Test
    fun `nothing changed, nothing sent`() = runTest {
        addIdentity()
        val vm = viewModel()
        vm.ready()
        vm.startEdit()
        vm.save()
        assertNull(vm.state.value.edit)
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an invalid email is refused before asking the node`() = runTest {
        addIdentity()
        val vm = viewModel()
        vm.ready()
        vm.startEdit()
        vm.onEmailChange("not an email")
        vm.save()
        assertTrue(vm.state.value.edit?.error != null)
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the passphrase the node asks for is asked, then sent`() = runTest {
        addIdentity()
        val passphrases = mutableListOf<String?>()
        every { client.updateProfile(any(), any()) } answers {
            passphrases += secondArg<String?>()
            if (secondArg<String?>() == null) failed(428) else CompletableFuture.completedFuture(null)
        }
        val vm = viewModel()
        vm.ready()
        vm.startEdit()
        vm.onNameChange("Alicia")
        vm.save()
        val asking = vm.state.first { it.edit?.needsPassphrase == true && it.edit?.busy == false }.edit!!
        assertEquals("passphrase needed", asking.error)

        vm.onPassphraseChange("secret")
        vm.save()
        vm.state.first { it.edit == null }
        assertEquals(listOf(null, "secret"), passphrases)
    }

    @Test
    fun `a wrong passphrase is reported as one, and cleared`() = runTest {
        addIdentity()
        every { client.profile } returns CompletableFuture.completedFuture(profile(name = "Alice", passphrase = true))
        every { client.updateProfile(any(), any()) } returns failed(403)
        val vm = viewModel()
        vm.ready()
        vm.startEdit()
        assertTrue(vm.state.value.edit!!.needsPassphrase)

        // Without the passphrase the account needs, nothing is sent.
        vm.onNameChange("Alicia")
        vm.save()
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }

        vm.onPassphraseChange("guess")
        vm.save()
        val edit = vm.state.first { it.edit?.error != null }.edit!!
        assertEquals("wrong passphrase", edit.error)
        assertEquals("", edit.passphrase)
    }

    @Test
    fun `too many wrong passphrases say how long to wait`() = runTest {
        addIdentity()
        every { client.profile } returns CompletableFuture.completedFuture(profile(name = "Alice", passphrase = true))
        every { client.updateProfile(any(), any()) } returns failed(429, "120")
        every { context.getString(io.bosonnetwork.identity.core.designsystem.R.string.ds_error_locked, any()) } returns "locked"
        val vm = viewModel()
        vm.ready()
        vm.startEdit()
        vm.onNameChange("Alicia")
        vm.onPassphraseChange("guess")
        vm.save()
        assertEquals("locked", vm.state.first { it.edit?.error != null }.edit!!.error)
    }
}
