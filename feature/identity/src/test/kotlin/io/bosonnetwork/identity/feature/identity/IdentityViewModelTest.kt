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
import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.Avatar
import io.bosonnetwork.director.client.AvatarRefresh
import io.bosonnetwork.director.client.AvatarChange
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.NodeStatus
import io.bosonnetwork.director.client.Profile
import io.bosonnetwork.director.client.SuperNodeCode
import io.bosonnetwork.director.client.ProfileUpdate
import io.bosonnetwork.director.client.exceptions.DirectorException
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.AdminAccess
import io.bosonnetwork.identity.core.boson.AvatarStore
import io.bosonnetwork.identity.core.boson.CardContent
import io.bosonnetwork.identity.core.boson.CardStatus
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.NodeLogos
import io.bosonnetwork.identity.core.boson.ProfileCards
import io.bosonnetwork.identity.core.boson.ProfileSync
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
import io.mockk.verify
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
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
        every { getString(R.string.id_sync_later) } returns "saved, later"
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

    private val avatars = AvatarStore(File(dir, "avatars"))
    private val avatarImages = mockk<AvatarImages>()

    private val profileSync by lazy { ProfileSync(vault, nodes, avatars) }

    private fun viewModel() =
        IdentityViewModel(vault, active, nodes, cards, logos, avatars, adminAccess, avatarImages, profileSync, context).also { viewModels += it }

    // The profile as the client would decode it; its constructor is the one Jackson uses.
    private fun profile(
        name: String? = null,
        bio: String? = null,
        email: String? = null,
        passphrase: Boolean = false,
        admin: Boolean = false,
        avatar: String? = null,
        revision: Long = 1,
    ): Profile =
        Profile::class.java.getDeclaredConstructor(
            Id::class.java, Boolean::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            Long::class.java, Long::class.java, String::class.java, Boolean::class.java,
        ).apply { isAccessible = true }.newInstance(Id.random(), admin, name, avatar, email, bio, 0L, 0L, "Free", passphrase)
            .also { p -> Profile::class.java.getDeclaredField("profileRevision").apply { isAccessible = true }.setLong(p, revision) }

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

    private fun <T> failed(status: Int, retryAfter: String? = null): CompletableFuture<T> =
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
    fun `the node QR code is the super node code the scanners read`() {
        val code = nodeQrPayload(node, nodeId.toString())
        assertEquals("boson:supernode:1:$nodeId:${node.url}", code)
        val read = SuperNodeCode.parse(code).orElseThrow()
        assertEquals(nodeId, read.nodeId.orElseThrow())
        assertEquals(node.url, read.url)
        assertEquals("node.example", node.host())
    }

    // ---- The avatar ----------------------------------------------------------------------------

    private val avatarUri get() = CardContent.avatarUri(nodeId.toString(), currentUser)
    private var currentUser = ""

    @Test
    fun `the node's avatar is downloaded once, then only when the profile moves`() = runTest {
        currentUser = addIdentity()
        every { client.profile } returns CompletableFuture.completedFuture(profile(name = "Alice", avatar = "bnr://x", revision = 1))
        every { client.avatar } returns CompletableFuture.completedFuture(Optional.of(Avatar.of("image/png", byteArrayOf(1, 2, 3), "\"v1\"", null)))
        val vm = viewModel()
        assertArrayEquals(byteArrayOf(1, 2, 3), vm.state.first { it.avatar != null }.avatar)

        vm.refresh()
        vm.state.first { it.node is NodeState.Ready && !it.refreshing }
        verify(exactly = 1) { client.avatar }

        // Changed on the node: a new revision, and a new picture.
        every { client.profile } returns CompletableFuture.completedFuture(profile(name = "Alice", avatar = "bnr://x", revision = 2))
        every { client.avatar } returns CompletableFuture.completedFuture(Optional.of(Avatar.of("image/png", byteArrayOf(9), "\"v2\"", null)))
        vm.refresh()
        assertArrayEquals(byteArrayOf(9), vm.state.first { it.avatar?.contentEquals(byteArrayOf(9)) == true }.avatar)

        // Removed on the node.
        every { client.profile } returns CompletableFuture.completedFuture(profile(name = "Alice", revision = 3))
        vm.refresh()
        assertNull(vm.state.first { it.avatar == null }.avatar)
    }

    // ---- Changing the avatar -------------------------------------------------------------------

    private val picked = mockk<Uri>()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7)

    private fun pickable(revision: Long = 2, image: ByteArray = jpeg) {
        coEvery { avatarImages.prepare(picked) } returns PreparedAvatar(image, mockk<Bitmap>())
        every { client.changeAvatar(image, "image/jpeg", any()) } answers {
            // The node has it now, at the revision it answers with.
            every { client.profile } returns CompletableFuture.completedFuture(profile(name = "Alice", avatar = "bnr://x", revision = revision))
            CompletableFuture.completedFuture(avatarChange(revision))
        }
    }

    private fun avatarChange(revision: Long): AvatarChange =
        AvatarChange::class.java.getDeclaredConstructor(String::class.java, Long::class.java)
            .apply { isAccessible = true }.newInstance("bnr://x", revision)

    @Test
    fun `a picked photo is shown to confirm, kept here, then sent to the node and shown`() = runTest {
        val userId = addIdentity()
        currentUser = userId
        pickable()
        val vm = viewModel()
        vm.ready()
        assertTrue(vm.state.value.canChangeAvatar)

        vm.onAvatarPicked(picked)
        val pick = vm.state.first { it.avatarPick != null }.avatarPick!!
        assertArrayEquals(jpeg, pick.image)
        verify(exactly = 0) { client.changeAvatar(any(), any(), any()) }

        vm.confirmAvatar()
        // Shown at once, from this phone; then on the node.
        vault.identities.first { !it.single().profile.dirty }
        val state = vm.state.first { it.avatarPick == null && it.avatar != null && !it.avatarBusy }
        assertArrayEquals(jpeg, state.avatar)
        assertEquals(avatarUri, (state.node as NodeState.Ready).profile.avatar)
        verify { client.changeAvatar(jpeg, "image/jpeg", 1L) }
        // Through this phone's key: no prompt for the user key, and no card to publish.
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { cards.publish(any(), any(), any<AuthPrompt>()) }
    }

    @Test
    fun `an identity without a home node keeps its avatar on this phone`() = runTest {
        val userId = addIdentity(homeNode = null)
        coEvery { avatarImages.prepare(picked) } returns PreparedAvatar(jpeg, mockk<Bitmap>())
        val vm = viewModel()
        vm.state.first { it.identity != null }
        assertTrue(vm.state.value.canChangeAvatar)
        vm.onAvatarPicked(picked)
        vm.state.first { it.avatarPick != null }
        vm.confirmAvatar()
        assertArrayEquals(jpeg, vm.state.first { it.avatar != null && it.avatarPick == null }.avatar)
        assertEquals(AvatarStore.digest(jpeg), vault.identities.value.single { it.userId == userId }.profile.fields.avatar)
        coVerify(exactly = 0) { nodes.deviceClient(any(), any()) }
    }

    @Test
    fun `no photo picked changes nothing, and an unreadable one says so`() = runTest {
        addIdentity()
        coEvery { avatarImages.prepare(picked) } throws AppError.InvalidInput("can't read it")
        val vm = viewModel()
        vm.ready()
        vm.onAvatarPicked(null)
        assertNull(vm.state.value.avatarPick)
        assertFalse(vm.state.value.avatarBusy)

        vm.onAvatarPicked(picked)
        assertEquals("can't read it", vm.state.first { it.avatarError != null }.avatarError)
        vm.dismissAvatarError()
        assertNull(vm.state.value.avatarError)
    }

    @Test
    fun `an unreachable node leaves the photo here for later, a refusing one says why`() = runTest {
        addIdentity()
        pickable()
        val vm = viewModel()
        vm.ready()
        vm.onAvatarPicked(picked)
        vm.state.first { it.avatarPick != null }

        every { client.changeAvatar(jpeg, "image/jpeg", any()) } returns
            CompletableFuture.failedFuture(DirectorException("no answer", java.io.IOException("refused")))
        vm.confirmAvatar()
        val later = vm.state.first { it.syncNote != null }
        assertNull(later.avatarPick)
        assertArrayEquals(jpeg, later.avatar)
        assertTrue(vault.identities.value.single().profile.dirty)

        every { client.changeAvatar(jpeg, "image/jpeg", any()) } returns
            CompletableFuture.failedFuture(DirectorException.fromResponse(413, "too big", null))
        vm.onAvatarPicked(picked)
        vm.state.first { it.avatarPick != null }
        vm.confirmAvatar()
        assertTrue(vm.state.first { it.avatarError != null }.avatarError!!.isNotEmpty())
    }

    @Test
    fun `a published card is published again when the avatar comes or goes, not when it changes`() = runTest {
        currentUser = addIdentity()
        pickable()
        coEvery { cards.status(any(), any()) } returns CardStatus.Published(3)
        val content = mutableListOf<CardContent>()
        coEvery { cards.publish(any(), capture(content), any<AuthPrompt>()) } returns 4
        val vm = viewModel()
        vm.ready()
        vm.card()

        // Comes: the card now names the avatar.
        vm.onAvatarPicked(picked)
        vm.state.first { it.avatarPick != null }
        vm.confirmAvatar()
        assertEquals(CardState.On(4), vm.state.first { it.card.state == CardState.On(4) }.card.state)
        assertEquals(avatarUri, content.single().avatar)

        // Changes: another picture at the same address, so the card stays as it is.
        val other = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 8)
        pickable(revision = 3, image = other)
        vm.onAvatarPicked(picked)
        vm.state.first { it.avatarPick != null }
        vm.confirmAvatar()
        vault.identities.first { it.single().profile.revision == 3L }
        verify { client.changeAvatar(other, "image/jpeg", 2L) }
        assertEquals(1, content.size)

        // Goes: the card no longer names one.
        every { client.removeAvatar(3L) } answers {
            every { client.profile } returns CompletableFuture.completedFuture(profile(name = "Alice", revision = 4))
            CompletableFuture.completedFuture(4L)
        }
        vm.removeAvatar()
        val state = vm.state.first { it.avatar == null && !it.avatarBusy && content.size == 2 }
        assertNull((state.node as NodeState.Ready).profile.avatar)
        assertNull(content.last().avatar)
    }

    @Test
    fun `the kept avatar shows at once, even while the node can't be reached`() = runTest {
        val userId = addIdentity()
        avatars.put(userId, Avatar.of("image/png", byteArrayOf(7), "\"v1\"", null))
        every { client.profile } returns CompletableFuture.failedFuture(DirectorException("no answer", java.io.IOException("refused")))
        val vm = viewModel()
        vm.state.first { it.node is NodeState.Failed }
        assertArrayEquals(byteArrayOf(7), vm.state.first { it.avatar != null }.avatar)
    }

    @Test
    fun `an avatar that can't be had leaves the identicon`() = runTest {
        addIdentity()
        every { client.profile } returns CompletableFuture.completedFuture(profile(name = "Alice", avatar = "bnr://x"))
        every { client.avatar } returns CompletableFuture.failedFuture(DirectorException.fromResponse(500, "oops", null))
        val vm = viewModel()
        assertEquals("Alice", vm.ready().profile.name)
        assertNull(vm.state.value.avatar)
    }

    @Test
    fun `the card names the avatar by the address the home node serves it at`() = runTest {
        currentUser = addIdentity()
        every { client.profile } returns CompletableFuture.completedFuture(profile(name = "Alice", avatar = "bnr://x"))
        every { client.avatar } returns CompletableFuture.completedFuture(Optional.of(Avatar.of("image/png", byteArrayOf(5), null, null)))
        val content = slot<CardContent>()
        coEvery { cards.publish(any(), capture(content), any<AuthPrompt>()) } returns 0
        val vm = viewModel()
        vm.card()
        vm.requestCard(true)
        vm.confirmCard()
        vm.state.first { it.card.state is CardState.On }
        assertEquals(avatarUri, content.captured.avatar)
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
    fun `a card that says something else than the profile is out of date, and Update publishes it again`() = runTest {
        addIdentity()
        // Published from another phone, before the name changed there.
        val old = CardContent("Al", null, null, nodeId.toString(), null)
        coEvery { cards.status(any(), any()) } returns CardStatus.Published(6, old)
        val content = slot<CardContent>()
        coEvery { cards.publish(any(), capture(content), any<AuthPrompt>()) } returns 7
        val vm = viewModel()
        vm.card()
        assertTrue(vm.state.first { it.card.state is CardState.On }.card.outdated)

        vm.updateCard()
        val card = vm.state.first { it.card.state == CardState.On(7) }.card
        assertFalse(card.outdated)
        assertEquals("Alice", content.captured.name)
    }

    @Test
    fun `a card that says what the profile does is not out of date`() = runTest {
        addIdentity()
        coEvery { cards.status(any(), any()) } returns CardStatus.Published(6, CardContent("Alice", null, null, nodeId.toString(), null))
        val vm = viewModel()
        vm.card()
        assertFalse(vm.state.value.card.outdated)
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
    fun `saving keeps the profile here, then sends the changed fields through this phone's key`() = runTest {
        addIdentity()
        val update = slot<ProfileUpdate>()
        every { client.updateProfile(capture(update), any()) } returns CompletableFuture.completedFuture(2L)
        val vm = viewModel()
        vm.ready()

        vm.startEdit()
        vm.onNameChange("  Alicia ")
        vm.onBioChange("Hello")
        vm.save()
        val state = vm.state.first { it.edit == null }
        assertEquals(mapOf("name" to "Alicia", "bio" to "Hello"), update.captured.sent())
        val view = (state.node as NodeState.Ready).profile
        assertEquals("Alicia", view.name)
        assertEquals("Hello", view.bio)
        val kept = vault.identities.first { it.single().name == "Alicia" }.single()
        assertEquals(2L, kept.profile.revision)
        assertFalse(kept.profile.dirty)
        // No card published: nothing to sign, so no prompt for the user key.
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { cards.publish(any(), any(), any<AuthPrompt>()) }
    }

    @Test
    fun `an identity without a home node edits its profile on this phone`() = runTest {
        val userId = addIdentity(homeNode = null)
        val vm = viewModel()
        vm.state.first { it.identity != null }
        vm.startEdit()
        assertFalse(vm.state.value.edit!!.needsPassphrase)
        vm.onNameChange("Local Alice")
        vm.onEmailChange("alice@example.com")
        vm.save()
        vm.state.first { it.edit == null }
        val kept = vault.identities.value.single { it.userId == userId }
        assertEquals("Local Alice", kept.name)
        assertEquals("alice@example.com", kept.profile.fields.email)
        coVerify(exactly = 0) { nodes.deviceClient(any(), any()) }
    }

    @Test
    fun `a node that can't be reached leaves the change here, to sync later`() = runTest {
        addIdentity()
        val vm = viewModel()
        vm.ready()
        every { client.profile } returns CompletableFuture.failedFuture(DirectorException("no answer", java.io.IOException("refused")))
        vm.startEdit()
        vm.onNameChange("Alicia")
        vm.save()
        val state = vm.state.first { it.edit == null }
        assertEquals("saved, later", state.syncNote)
        assertEquals("Alicia", vault.identities.value.single().name)
        assertTrue(vault.identities.value.single().profile.dirty)
    }

    @Test
    fun `a published card is updated with the profile`() = runTest {
        addIdentity()
        coEvery { cards.status(any(), any()) } returns CardStatus.Published(4)
        every { client.updateProfile(any(), any()) } returns CompletableFuture.completedFuture(2L)
        val content = slot<CardContent>()
        coEvery { cards.publish(any(), capture(content), any<AuthPrompt>()) } returns 5
        val vm = viewModel()
        vm.card()

        vm.startEdit()
        assertTrue(vm.state.value.edit!!.updatesCard)
        vm.onNameChange("Alicia")
        vm.save()
        val state = vm.state.first { it.edit == null }
        assertEquals(CardState.On(5), state.card.state)
        assertEquals("Alicia", content.captured.name)
    }

    @Test
    fun `the profile is saved even when its card can't be updated`() = runTest {
        addIdentity()
        coEvery { cards.status(any(), any()) } returns CardStatus.Published(4)
        every { client.updateProfile(any(), any()) } returns CompletableFuture.completedFuture(2L)
        coEvery { cards.publish(any(), any(), any<AuthPrompt>()) } throws AppError.Network("down")
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
        every { client.updateProfile(any(), any()) } returns CompletableFuture.completedFuture(2L)
        val vm = viewModel()
        vm.card()
        vm.startEdit()
        vm.onEmailChange("a@example.com")
        vm.save()
        vm.state.first { it.edit == null }
        coVerify(exactly = 0) { cards.publish(any(), any(), any<AuthPrompt>()) }
    }

    @Test
    fun `nothing changed, nothing sent`() = runTest {
        addIdentity()
        val vm = viewModel()
        vm.ready()
        vm.startEdit()
        vm.save()
        assertNull(vm.state.value.edit)
        verify(exactly = 0) { client.updateProfile(any(), any()) }
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
        verify(exactly = 0) { client.updateProfile(any(), any()) }
    }

    @Test
    fun `the passphrase the node asks for is asked, then sent`() = runTest {
        addIdentity()
        val passphrases = mutableListOf<String?>()
        every { client.updateProfile(any(), any()) } answers {
            passphrases += secondArg<String?>()
            if (secondArg<String?>() == null) failed(428) else CompletableFuture.completedFuture(2L)
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
        verify(exactly = 0) { client.updateProfile(any(), any()) }

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
