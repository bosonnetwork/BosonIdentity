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
import io.bosonnetwork.director.client.Avatar
import io.bosonnetwork.director.client.AvatarChange
import io.bosonnetwork.director.client.AvatarRefresh
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.Profile
import io.bosonnetwork.director.client.ProfileUpdate
import io.bosonnetwork.director.client.exceptions.DirectorException
import io.bosonnetwork.director.client.exceptions.ProfileChangedException
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.model.ProfileFields
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.core.vault.KeyProtector
import io.bosonnetwork.identity.core.vault.Protection
import io.bosonnetwork.identity.core.vault.Sealed
import io.bosonnetwork.identity.core.vault.VaultStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Syncing the profile kept on this phone with the home node, over a real vault and avatar store and a mocked node. */
class ProfileSyncTest {

    private class NoKeystore : KeyProtector {
        override fun isAvailable() = true
        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt) =
            Sealed(ByteArray(12), plaintext.copyOf())
        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt) = sealed.data.copyOf()
        override fun delete(alias: String) = Unit
    }

    private val dir: File = Files.createTempDirectory("sync").toFile()
    private val vault = IdentityVault(VaultStore(File(dir, "vault")), NoKeystore())
    private val avatars = AvatarStore(File(dir, "avatars"))
    private val client = mockk<DirectorClient>()
    private val nodes = mockk<NodeConnections>()
    private val sync = ProfileSync(vault, nodes, avatars)
    private val prompt = AuthPrompt("Unlock")
    private val node = NodeAddress("https://node.example", Id.random().toString())

    private val nodePicture = byteArrayOf(1, 2, 3)
    private val phonePicture = byteArrayOf(9, 8, 7)

    @Before
    fun setUp() {
        coEvery { nodes.deviceClient(any(), any()) } returns client
        every { client.refreshAvatar(any()) } returns CompletableFuture.completedFuture(
            mockk<AvatarRefresh> { every { status } returns AvatarRefresh.Status.UNCHANGED },
        )
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private suspend fun identity(homeNode: NodeAddress? = node, name: String? = null): String {
        val userId = vault.add(Signature.KeyPair.random(), name, true, keepUserKey = true, prompt = prompt).userId
        if (homeNode != null) vault.update(userId) { it.copy(homeNode = homeNode) }
        return userId
    }

    private fun info(userId: String) = vault.identities.value.single { it.userId == userId }

    // The profile as the client would decode it; its constructor is the one Jackson uses, the revision
    // the field it sets after.
    private fun profile(revision: Long, name: String? = null, bio: String? = null, email: String? = null, avatar: Boolean = false): Profile =
        Profile::class.java.getDeclaredConstructor(
            Id::class.java, Boolean::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            Long::class.java, Long::class.java, String::class.java, Boolean::class.java,
        ).apply { isAccessible = true }
            .newInstance(Id.random(), false, name, if (avatar) "bnr://node/api/v1/client/avatar/x" else null, email, bio, 0L, 0L, "Free", false)
            .also { p -> Profile::class.java.getDeclaredField("profileRevision").apply { isAccessible = true }.setLong(p, revision) }

    private fun nodeHas(profile: Profile) {
        every { client.profile } returns CompletableFuture.completedFuture(profile)
    }

    private fun avatarChange(revision: Long): AvatarChange =
        AvatarChange::class.java.getDeclaredConstructor(String::class.java, Long::class.java)
            .apply { isAccessible = true }.newInstance("bnr://node/api/v1/client/avatar/x", revision)

    // ProfileUpdate keeps its fields to itself; the test reads what would be sent.
    private fun ProfileUpdate.sent(): Map<*, *> =
        ProfileUpdate::class.java.getDeclaredMethod("fields").apply { isAccessible = true }.invoke(this) as Map<*, *>

    private fun ProfileUpdate.condition(): Long? =
        ProfileUpdate::class.java.getDeclaredMethod("ifRevision").apply { isAccessible = true }.invoke(this) as Long?

    // ---- The merge -----------------------------------------------------------------------------

    @Test
    fun `the merge takes each side's own changes, and the node's where both changed`() {
        val base = ProfileFields(name = "Ann", bio = "Hi", email = "a@x")
        val local = ProfileFields(name = "Anna", bio = "Hi", email = "ann@x")
        val remote = ProfileFields(name = "Annie", bio = "Hello", email = "a@x")
        assertEquals(ProfileFields(name = "Annie", bio = "Hello", email = "ann@x"), ProfileSync.merge(base, local, remote))
    }

    @Test
    fun `a first merge takes the node's values and fills its empty fields from this phone`() {
        val local = ProfileFields(name = "Phone", bio = "From the phone", avatar = "pic")
        val remote = ProfileFields(name = "Node")
        assertEquals(ProfileFields(name = "Node", bio = "From the phone", avatar = "pic"), ProfileSync.merge(null, local, remote))
    }

    // ---- Syncing -------------------------------------------------------------------------------

    @Test
    fun `without a home node there is nothing to sync`() = runTest {
        val userId = identity(homeNode = null)
        assertEquals(SyncOutcome.NoNode, sync.sync(userId, prompt))
    }

    @Test
    fun `the node's profile is taken, then only checked`() = runTest {
        val userId = identity()
        nodeHas(profile(3, name = "Alice", bio = "Hi", email = "a@example.com", avatar = true))
        every { client.avatar } returns CompletableFuture.completedFuture(Optional.of(Avatar.of("image/jpeg", nodePicture, "\"v\"", null)))

        assertEquals(SyncOutcome.Pushed.javaClass.superclass, SyncOutcome.Pulled.javaClass.superclass)
        val first = sync.sync(userId, prompt)
        val profile = info(userId).profile
        assertEquals(ProfileFields("Alice", "Hi", "a@example.com", AvatarStore.digest(nodePicture)), profile.fields)
        assertEquals(3L, profile.revision)
        assertFalse(profile.dirty)
        assertArrayEquals(nodePicture, avatars.avatars.value[userId])
        assertTrue(first == SyncOutcome.Pulled || first == SyncOutcome.Pushed)

        assertEquals(SyncOutcome.UpToDate, sync.sync(userId, prompt))
        verify(exactly = 1) { client.avatar }
    }

    @Test
    fun `this phone's changes go to the node, conditional on the revision synced`() = runTest {
        val userId = identity()
        nodeHas(profile(3, name = "Alice"))
        sync.sync(userId, prompt)

        sync.edit(userId, "Alicia", "Hello", null)
        val update = slot<ProfileUpdate>()
        every { client.updateProfile(capture(update), null) } returns CompletableFuture.completedFuture(5L)
        assertEquals(SyncOutcome.Pushed, sync.sync(userId, prompt))
        assertEquals(mapOf("name" to "Alicia", "bio" to "Hello"), update.captured.sent())
        assertEquals(3L, update.captured.condition())
        assertEquals(5L, info(userId).profile.revision)
        assertFalse(info(userId).profile.dirty)
    }

    @Test
    fun `changes on both sides are merged, the node's kept where both changed`() = runTest {
        val userId = identity()
        nodeHas(profile(3, name = "Alice", bio = "Hi"))
        sync.sync(userId, prompt)

        sync.edit(userId, "Alicia", "Hi", "alice@example.com")
        nodeHas(profile(4, name = "Ally", bio = "Hi"))
        val update = slot<ProfileUpdate>()
        every { client.updateProfile(capture(update), null) } returns CompletableFuture.completedFuture(5L)

        val outcome = sync.sync(userId, prompt)
        assertEquals(SyncOutcome.Merged(setOf("name")), outcome)
        assertEquals(mapOf("email" to "alice@example.com"), update.captured.sent())
        assertEquals(4L, update.captured.condition())
        assertEquals(ProfileFields(name = "Ally", bio = "Hi", email = "alice@example.com"), info(userId).profile.fields)
    }

    @Test
    fun `a change made meanwhile on the node is synced again, once`() = runTest {
        val userId = identity()
        nodeHas(profile(3, name = "Alice"))
        sync.sync(userId, prompt)
        sync.edit(userId, "Alicia", null, null)

        var calls = 0
        every { client.updateProfile(any(), null) } answers {
            calls++
            if (calls == 1) {
                nodeHas(profile(4, name = "Alice", bio = "Set elsewhere"))
                CompletableFuture.failedFuture(ProfileChangedException("moved"))
            } else {
                CompletableFuture.completedFuture(5L)
            }
        }
        assertEquals(SyncOutcome.Pushed, sync.sync(userId, prompt))
        assertEquals(2, calls)
        assertEquals(ProfileFields(name = "Alicia", bio = "Set elsewhere"), info(userId).profile.fields)
    }

    @Test
    fun `the avatar set or removed here goes to the node`() = runTest {
        val userId = identity()
        nodeHas(profile(3, name = "Alice"))
        sync.sync(userId, prompt)

        sync.setAvatar(userId, phonePicture)
        assertTrue(info(userId).profile.dirty)
        every { client.changeAvatar(phonePicture, "image/jpeg", 3L) } returns CompletableFuture.completedFuture(avatarChange(4))
        assertEquals(SyncOutcome.Pushed, sync.sync(userId, prompt))
        assertEquals(AvatarStore.digest(phonePicture), info(userId).profile.fields.avatar)
        assertEquals(4L, info(userId).profile.revision)

        // The node now has the picture, at the revision it answered with.
        nodeHas(profile(4, name = "Alice", avatar = true))
        sync.setAvatar(userId, null)
        every { client.removeAvatar(4L) } returns CompletableFuture.completedFuture(5L)
        assertEquals(SyncOutcome.Pushed, sync.sync(userId, prompt))
        assertNull(info(userId).profile.fields.avatar)
        assertNull(avatars.avatars.value[userId])
        assertEquals(5L, info(userId).profile.revision)
    }

    @Test
    fun `an account with a passphrase keeps the change here until it is given`() = runTest {
        val userId = identity()
        nodeHas(profile(3, name = "Alice"))
        sync.sync(userId, prompt)
        sync.edit(userId, "Alicia", null, null)

        every { client.updateProfile(any(), null) } returns
            CompletableFuture.failedFuture(DirectorException.fromResponse(428, "passphrase", null))
        assertEquals(SyncOutcome.NeedsPassphrase, sync.sync(userId, prompt))
        assertTrue(info(userId).profile.dirty)

        every { client.updateProfile(any(), "secret") } returns CompletableFuture.completedFuture(4L)
        assertEquals(SyncOutcome.Pushed, sync.sync(userId, prompt, "secret"))
        assertFalse(info(userId).profile.dirty)
    }

    @Test
    fun `a picture that can't be downloaded is fetched again next time, the text taken now`() = runTest {
        val userId = identity()
        nodeHas(profile(3, name = "Alice"))
        sync.sync(userId, prompt)

        nodeHas(profile(4, name = "Ally", avatar = true))
        every { client.avatar } returns CompletableFuture.failedFuture(DirectorException.fromResponse(500, "oops", null))
        assertEquals(SyncOutcome.Pulled, sync.sync(userId, prompt))
        assertEquals("Ally", info(userId).name)
        assertEquals(3L, info(userId).profile.revision)

        every { client.avatar } returns CompletableFuture.completedFuture(Optional.of(Avatar.of("image/jpeg", nodePicture, null, null)))
        assertEquals(SyncOutcome.Pulled, sync.sync(userId, prompt))
        assertEquals(4L, info(userId).profile.revision)
        assertArrayEquals(nodePicture, avatars.avatars.value[userId])
    }

    @Test
    fun `a profile made here before joining fills the node's empty fields`() = runTest {
        val userId = identity(name = "Phone name")
        sync.edit(userId, "Phone name", "Made on the phone", null)
        sync.setAvatar(userId, phonePicture)
        nodeHas(profile(1, name = "Phone name"))
        val update = slot<ProfileUpdate>()
        every { client.updateProfile(capture(update), null) } returns CompletableFuture.completedFuture(2L)
        every { client.changeAvatar(phonePicture, "image/jpeg", 2L) } returns CompletableFuture.completedFuture(avatarChange(3))

        assertEquals(SyncOutcome.Pushed, sync.sync(userId, prompt))
        assertEquals(mapOf("bio" to "Made on the phone"), update.captured.sent())
        assertEquals(3L, info(userId).profile.revision)
        assertFalse(info(userId).profile.dirty)
    }
}
