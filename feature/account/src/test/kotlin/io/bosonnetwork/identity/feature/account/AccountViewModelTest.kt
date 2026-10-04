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

package io.bosonnetwork.identity.feature.account

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.Profile
import io.bosonnetwork.director.client.ProfileUpdate
import io.bosonnetwork.director.client.exceptions.DirectorException
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.model.AppError
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
 * The account screen's logic, over a real vault (with a stand-in for the Keystore), a real
 * DataStore for the identity in use, and a mocked node.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {

    // Seals nothing: the account never opens a key itself; the node connections are mocked.
    private class NoKeystore : KeyProtector {
        override fun isAvailable() = true
        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt) =
            Sealed(ByteArray(12), plaintext.copyOf())
        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt) = sealed.data.copyOf()
        override fun delete(alias: String) = Unit
    }

    private val node = NodeAddress("https://node.example", "nodeid")
    private val dir: File = Files.createTempDirectory("account").toFile()
    private val vault = IdentityVault(VaultStore(File(dir, "vault")), NoKeystore())
    private val storeJob = Job()
    private val active = ActiveIdentity(
        vault,
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob)) { File(dir, "active.preferences_pb") },
    )
    private val client = mockk<DirectorClient>()
    private val nodes = mockk<NodeConnections>()
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.acc_wrong_passphrase) } returns "wrong passphrase"
        every { getString(io.bosonnetwork.identity.core.designsystem.R.string.ds_error_passphrase_required) } returns "passphrase needed"
    }
    private val viewModels = mutableListOf<AccountViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { nodes.deviceClient(any(), any()) } returns client
        coEvery { nodes.forget(any()) } returns Unit
        coEvery { nodes.asUser<Any?>(any(), any(), any(), any(), any()) } coAnswers {
            arg<suspend (DirectorClient, Signature.KeyPair?) -> Any?>(4).invoke(client, null)
        }
        every { client.profile } returns CompletableFuture.completedFuture(profile(name = "Alice"))
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

    private fun viewModel() = AccountViewModel(vault, active, nodes, context).also { viewModels += it }

    // The profile as the client would decode it; its constructor is the one Jackson uses.
    private fun profile(name: String? = null, bio: String? = null, email: String? = null, passphrase: Boolean = false): Profile =
        Profile::class.java.getDeclaredConstructor(
            Id::class.java, Boolean::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            Long::class.java, Long::class.java, String::class.java, Boolean::class.java,
        ).apply { isAccessible = true }.newInstance(Id.random(), false, name, null, email, bio, 0L, 0L, "Free", passphrase)

    private suspend fun addIdentity(homeNode: NodeAddress? = node, backupVerified: Boolean = true): String {
        val userId = vault.add(Signature.KeyPair.random(), null, backupVerified, keepUserKey = true, prompt = AuthPrompt("Confirm")).userId
        if (homeNode != null) vault.update(userId) { it.copy(homeNode = homeNode) }
        return userId
    }

    private suspend fun AccountViewModel.ready(): ProfileView =
        (state.first { it.profile is ProfileState.Ready }.profile as ProfileState.Ready).view

    // ProfileUpdate keeps its fields to itself; the test reads what would be sent.
    private fun ProfileUpdate.sent(): Map<*, *> =
        ProfileUpdate::class.java.getDeclaredMethod("fields").apply { isAccessible = true }.invoke(this) as Map<*, *>

    private fun failed(status: Int, retryAfter: String? = null): CompletableFuture<Void> =
        CompletableFuture.failedFuture(DirectorException.fromResponse(status, "refused", retryAfter))

    @Test
    fun `the profile of the identity in use is shown, and its name remembered`() = runTest {
        val userId = addIdentity()
        every { client.profile } returns CompletableFuture.completedFuture(profile(name = "Alice", email = "a@example.com"))
        val view = viewModel().ready()
        assertEquals("Alice", view.name)
        assertEquals("a@example.com", view.email)
        assertNull(view.bio)
        assertEquals("Free", view.plan)
        assertEquals("Alice", vault.identities.first { it.single().name != null }.single { it.userId == userId }.name)
    }

    @Test
    fun `without a home node there is no profile to ask for`() = runTest {
        addIdentity(homeNode = null)
        val vm = viewModel()
        val state = vm.state.first { it.identity != null }
        assertEquals(ProfileState.None, state.profile)
        coVerify(exactly = 0) { nodes.deviceClient(any(), any()) }
    }

    @Test
    fun `a dismissed unlock prompt leaves the profile locked`() = runTest {
        addIdentity()
        coEvery { nodes.deviceClient(any(), any()) } throws AppError.AuthenticationCancelled("no")
        val vm = viewModel()
        assertEquals(ProfileState.Locked, vm.state.first { it.profile == ProfileState.Locked }.profile)
    }

    @Test
    fun `switching identities shows the other one`() = runTest {
        val first = addIdentity()
        val second = addIdentity()
        val vm = viewModel()
        vm.ready()
        assertEquals(first, vm.state.value.identity?.userId)

        every { client.profile } returns CompletableFuture.completedFuture(profile(name = "Bob"))
        active.select(second)
        val state = vm.state.first { it.identity?.userId == second && it.profile is ProfileState.Ready }
        assertEquals("Bob", (state.profile as ProfileState.Ready).view.name)
    }

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
        val view = (state.profile as ProfileState.Ready).view
        assertEquals("Alicia", view.name)
        assertEquals("Hello", view.bio)
        assertEquals("Alicia", vault.identities.first { it.single().name == "Alicia" }.single().name)
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

    @Test
    fun `removing needs the first four characters, and the last one leads back to onboarding`() = runTest {
        val userId = addIdentity(backupVerified = false)
        val vm = viewModel()
        vm.state.first { it.identity != null }

        vm.remove("xxxx")
        assertEquals(1, vault.identities.value.size)

        vm.remove(removalCode(userId))
        assertTrue(vm.state.first { it.noIdentityLeft }.noIdentityLeft)
        assertTrue(vault.identities.value.isEmpty())
        coVerify { nodes.forget(userId) }
    }

    @Test
    fun `removing signs this phone out of the node first`() = runTest {
        val userId = addIdentity()
        val deviceId = Id.random()
        vault.update(userId) { it.copy(deviceId = deviceId.toString()) }
        every { client.removeDevice(deviceId, null) } returns CompletableFuture.completedFuture(null)
        val vm = viewModel()
        vm.state.first { it.identity?.deviceId != null }

        vm.remove(removalCode(userId))
        vm.state.first { it.noIdentityLeft }
        coVerify { client.removeDevice(deviceId, null) }
    }

    @Test
    fun `a passphrase-protected account asks for it before signing the phone out`() = runTest {
        val userId = addIdentity()
        val deviceId = Id.random()
        vault.update(userId) { it.copy(deviceId = deviceId.toString()) }
        every { client.removeDevice(deviceId, null) } returns CompletableFuture.failedFuture(DirectorException(428, "Passphrase required"))
        every { client.removeDevice(deviceId, "secret phrase") } returns CompletableFuture.completedFuture(null)
        val vm = viewModel()
        vm.state.first { it.identity?.deviceId != null }

        vm.remove(removalCode(userId))
        assertTrue(vm.state.first { it.removal?.needsPassphrase == true }.removal!!.needsPassphrase)
        assertEquals(1, vault.identities.value.size)

        vm.remove(removalCode(userId), "secret phrase")
        vm.state.first { it.noIdentityLeft }
        assertNull(vm.state.value.removal)
    }

    @Test
    fun `an unreachable node can be skipped, removing the identity anyway`() = runTest {
        val userId = addIdentity()
        val deviceId = Id.random()
        vault.update(userId) { it.copy(deviceId = deviceId.toString()) }
        every { client.removeDevice(deviceId, null) } returns
            CompletableFuture.failedFuture(DirectorException("no answer", java.io.IOException("refused")))
        val vm = viewModel()
        vm.state.first { it.identity?.deviceId != null }

        vm.remove(removalCode(userId))
        assertTrue(vm.state.first { it.removal?.unreachable != null }.removal!!.unreachable != null)
        assertEquals(1, vault.identities.value.size)

        vm.remove(removalCode(userId), force = true)
        vm.state.first { it.noIdentityLeft }
    }

    @Test
    fun `removing one of two shows the other`() = runTest {
        val first = addIdentity()
        val second = addIdentity()
        active.select(second)
        val vm = viewModel()
        vm.state.first { it.identity?.userId == second }

        vm.remove(removalCode(second))
        val state = vm.state.first { it.identity?.userId == first }
        assertFalse(state.noIdentityLeft)
        assertEquals(listOf(first), vault.identities.value.map { it.userId })
    }

    @Test
    fun `the node QR code is what the scanner reads`() {
        val json = JSONObject(nodeQrPayload(node))
        assertEquals(node.url, json.getString("url"))
        assertEquals(node.nodeId, json.getString("id"))
        assertFalse(JSONObject(nodeQrPayload(NodeAddress("https://ca.example"))).has("id"))
        assertEquals("node.example", node.host())
    }
}
