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

package io.bosonnetwork.identity.feature.devices

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.Device
import io.bosonnetwork.director.client.DirectorClient
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
import io.mockk.verify
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The device list's logic, over a real vault (with a stand-in for the Keystore), a real DataStore
 * for the identity in use, and a mocked node.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {

    // Seals nothing: the device list never opens a key itself; the node connections are mocked.
    private class NoKeystore : KeyProtector {
        override fun isAvailable() = true
        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt) =
            Sealed(ByteArray(12), plaintext.copyOf())
        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt) = sealed.data.copyOf()
        override fun delete(alias: String) = Unit
    }

    private val node = NodeAddress("https://node.example", "nodeid")
    private val dir: File = Files.createTempDirectory("devices").toFile()
    private val vault = IdentityVault(VaultStore(File(dir, "vault")), NoKeystore())
    private val storeJob = Job()
    private val active = ActiveIdentity(
        vault,
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob)) { File(dir, "active.preferences_pb") },
    )
    private val client = mockk<DirectorClient>()
    private val nodes = mockk<NodeConnections>()
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.dev_wrong_passphrase) } returns "wrong passphrase"
        every { getString(io.bosonnetwork.identity.core.designsystem.R.string.ds_error_passphrase_required) } returns "passphrase needed"
    }
    private val viewModels = mutableListOf<DevicesViewModel>()

    private val phoneId = Id.random()
    private val laptop = device(name = "Laptop", app = "Photon", lastSeen = 2_000)
    private val tablet = device(name = "Tablet", app = "Photon", lastSeen = 3_000, admin = true)
    private val passkey = device(name = "Chrome", app = "Portal", kind = Device.KIND_PASSKEY, recovery = true)
    private val phone = device(id = phoneId, name = "Pixel 9", app = "Boson Identity", lastSeen = 5_000)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { nodes.deviceClient(any(), any()) } returns client
        coEvery { nodes.asUser<Any?>(any(), any(), any(), any(), any()) } coAnswers {
            arg<suspend (DirectorClient, Signature.KeyPair?) -> Any?>(4).invoke(client, null)
        }
        every { client.listDevices() } returns CompletableFuture.completedFuture(listOf(laptop, passkey, phone, tablet))
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        storeJob.cancel()
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun viewModel() = DevicesViewModel(active, nodes, context).also { viewModels += it }

    // The device as the client would decode it; its constructor is the one Jackson uses.
    private fun device(
        id: Id = Id.random(),
        name: String = "",
        app: String = "",
        kind: String = Device.KIND_APP,
        admin: Boolean = false,
        recovery: Boolean = false,
        lastSeen: Long = 0,
    ): Device =
        Device::class.java.getDeclaredConstructor(
            Id::class.java, Id::class.java, String::class.java, String::class.java, String::class.java,
            Boolean::class.java, Boolean::class.java, Long::class.java, Long::class.java, Long::class.java, String::class.java,
        ).apply { isAccessible = true }.newInstance(id, Id.random(), name, app, kind, admin, recovery, 1_000L, 1_000L, lastSeen, "203.0.113.7")

    private suspend fun addIdentity(homeNode: NodeAddress? = node, backupVerified: Boolean = true): String {
        val userId = vault.add(Signature.KeyPair.random(), null, backupVerified, keepUserKey = true, prompt = AuthPrompt("Confirm")).userId
        vault.update(userId) { it.copy(homeNode = homeNode, deviceId = phoneId.toString()) }
        return userId
    }

    private suspend fun DevicesViewModel.ready(): DeviceGroups =
        (state.first { it.devices is DevicesState.Ready && it.identity?.homeNode != null }.devices as DevicesState.Ready).groups

    private fun failed(status: Int): CompletableFuture<Void> =
        CompletableFuture.failedFuture(DirectorException.fromResponse(status, "refused", null))

    @Test
    fun `devices are grouped, this phone first, the most recently seen first`() = runTest {
        addIdentity()
        val groups = viewModel().ready()
        assertEquals(phoneId.toString(), groups.thisPhone?.id)
        assertTrue(groups.thisPhone!!.thisPhone)
        assertEquals(listOf("Tablet", "Laptop"), groups.apps.map { it.name })
        assertEquals(listOf("Chrome"), groups.passkeys.map { it.name })
        assertTrue(groups.apps.first().admin)
        assertTrue(groups.passkeys.single().recoveryMethod)
        assertEquals("203.0.113.7", groups.passkeys.single().lastAddress)
        // Listing is routine: this app's device key, never the user key.
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `without a home node there are no devices to ask for`() = runTest {
        addIdentity(homeNode = null)
        val vm = viewModel()
        assertEquals(DevicesState.None, vm.state.first { it.identity != null }.devices)
        coVerify(exactly = 0) { nodes.deviceClient(any(), any()) }
    }

    @Test
    fun `a dismissed unlock prompt leaves the list locked, and refresh asks again`() = runTest {
        addIdentity()
        coEvery { nodes.deviceClient(any(), any()) } throws AppError.AuthenticationCancelled("no")
        val vm = viewModel()
        vm.state.first { it.devices == DevicesState.Locked }

        coEvery { nodes.deviceClient(any(), any()) } returns client
        vm.refresh()
        vm.ready()
    }

    @Test
    fun `a failure is shown, and refresh tries again`() = runTest {
        addIdentity()
        every { client.listDevices() } returns CompletableFuture.failedFuture(DirectorException("no answer", java.io.IOException("refused")))
        val vm = viewModel()
        vm.state.first { it.devices is DevicesState.Failed }

        every { client.listDevices() } returns CompletableFuture.completedFuture(listOf(phone))
        vm.refresh()
        assertTrue(vm.ready().apps.isEmpty())
    }

    @Test
    fun `renaming goes through this phone's key and updates the list`() = runTest {
        addIdentity()
        val renamed = device(id = laptop.id, name = "Work laptop", app = "Photon", lastSeen = 2_000)
        every { client.renameDevice(laptop.id, "Work laptop") } returns CompletableFuture.completedFuture(renamed)
        val vm = viewModel()
        val view = vm.ready().apps.single { it.name == "Laptop" }

        vm.startRename(view)
        vm.onRenameChange("  Work laptop ")
        vm.rename()
        val state = vm.state.first { it.rename == null }
        assertEquals(setOf("Tablet", "Work laptop"), (state.devices as DevicesState.Ready).groups.apps.map { it.name }.toSet())
        verify { client.renameDevice(laptop.id, "Work laptop") }
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an empty or too long name is not sent`() = runTest {
        addIdentity()
        val vm = viewModel()
        val view = vm.ready().apps.first()
        vm.startRename(view)
        vm.onRenameChange("   ")
        vm.rename()
        vm.onRenameChange("x".repeat(MAX_DEVICE_NAME + 1))
        vm.rename()
        assertTrue(vm.state.value.rename != null)
        verify(exactly = 0) { client.renameDevice(any(), any()) }
    }

    @Test
    fun `removing confirms with the user key, then lists again`() = runTest {
        val userId = addIdentity()
        every { client.removeDevice(laptop.id, null) } returns CompletableFuture.completedFuture(null)
        val vm = viewModel()
        val view = vm.ready().apps.single { it.name == "Laptop" }

        every { client.listDevices() } returns CompletableFuture.completedFuture(listOf(passkey, phone, tablet))
        vm.startRemove(view)
        vm.remove()
        val state = vm.state.first { it.removal == null && !it.refreshing }
        assertEquals(listOf("Tablet"), (state.devices as DevicesState.Ready).groups.apps.map { it.name })
        coVerify { nodes.asUser<Any?>(userId, node, any(), false, any()) }
        verify { client.removeDevice(laptop.id, null) }
        verify(exactly = 2) { client.listDevices() }
    }

    @Test
    fun `the passphrase the node asks for is asked, then sent`() = runTest {
        addIdentity()
        val passphrases = mutableListOf<String?>()
        every { client.removeDevice(laptop.id, any()) } answers {
            passphrases += secondArg<String?>()
            if (secondArg<String?>() == null) failed(428) else CompletableFuture.completedFuture(null)
        }
        val vm = viewModel()
        vm.startRemove(vm.ready().apps.single { it.name == "Laptop" })
        vm.remove()
        val asking = vm.state.first { it.removal?.needsPassphrase == true && it.removal?.busy == false }.removal!!
        assertEquals("passphrase needed", asking.error)

        // Without the passphrase, nothing more is sent.
        vm.remove()
        assertEquals(listOf<String?>(null), passphrases)

        vm.onRemovePassphraseChange("secret")
        vm.remove()
        vm.state.first { it.removal == null }
        assertEquals(listOf(null, "secret"), passphrases)
    }

    @Test
    fun `a wrong passphrase is reported as one, and cleared`() = runTest {
        addIdentity()
        every { client.removeDevice(laptop.id, null) } returns failed(428)
        every { client.removeDevice(laptop.id, "guess") } returns failed(403)
        val vm = viewModel()
        vm.startRemove(vm.ready().apps.single { it.name == "Laptop" })
        vm.remove()
        vm.state.first { it.removal?.needsPassphrase == true }

        vm.onRemovePassphraseChange("guess")
        vm.remove()
        val removal = vm.state.first { it.removal?.error == "wrong passphrase" }.removal!!
        assertEquals("", removal.passphrase)
        assertTrue(removal.needsPassphrase)
    }

    @Test
    fun `without a checked backup, removing is refused and the backup offered`() = runTest {
        addIdentity(backupVerified = false)
        val vm = viewModel()
        val view = vm.ready().apps.first()
        vm.startRemove(view)
        assertTrue(vm.state.value.backupNeeded)
        assertNull(vm.state.value.removal)
        vm.remove()
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }

        vm.dismissBackupNeeded()
        assertFalse(vm.state.value.backupNeeded)
    }

    @Test
    fun `this phone can't be removed from the list`() = runTest {
        addIdentity()
        val vm = viewModel()
        vm.startRemove(vm.ready().thisPhone!!)
        assertNull(vm.state.value.removal)
        assertFalse(vm.state.value.backupNeeded)
    }
}
