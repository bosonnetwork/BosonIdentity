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

package io.bosonnetwork.identity.feature.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.exceptions.DirectorException
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.NodeConnections
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
 * Removing an identity from this phone, over a real vault (with a stand-in for the Keystore), a real
 * DataStore for the identity in use, and a mocked node.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    // Seals nothing: the node connections are mocked.
    private class NoKeystore : KeyProtector {
        override fun isAvailable() = true
        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt) =
            Sealed(ByteArray(12), plaintext.copyOf())
        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt) = sealed.data.copyOf()
        override fun delete(alias: String) = Unit
    }

    private val node = NodeAddress("https://node.example", "nodeid")
    private val dir: File = Files.createTempDirectory("settings").toFile()
    private val vault = IdentityVault(VaultStore(File(dir, "vault")), NoKeystore())
    private val storeJob = Job()
    private val active = ActiveIdentity(
        vault,
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob)) { File(dir, "active.preferences_pb") },
    )
    private val client = mockk<DirectorClient>()
    private val nodes = mockk<NodeConnections>()
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.set_wrong_passphrase) } returns "wrong passphrase"
    }
    private val viewModels = mutableListOf<SettingsViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { nodes.forget(any()) } returns Unit
        coEvery { nodes.asUser<Any?>(any(), any(), any(), any(), any()) } coAnswers {
            arg<suspend (DirectorClient, Signature.KeyPair?) -> Any?>(4).invoke(client, null)
        }
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

    private fun viewModel() = SettingsViewModel(vault, active, nodes, context).also { viewModels += it }

    private suspend fun addIdentity(homeNode: NodeAddress? = node, backupVerified: Boolean = true): String {
        val userId = vault.add(Signature.KeyPair.random(), null, backupVerified, keepUserKey = true, prompt = AuthPrompt("Confirm")).userId
        if (homeNode != null) vault.update(userId) { it.copy(homeNode = homeNode) }
        return userId
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
    fun `a wrong passphrase is said to be one`() = runTest {
        val userId = addIdentity()
        val deviceId = Id.random()
        vault.update(userId) { it.copy(deviceId = deviceId.toString()) }
        every { client.removeDevice(deviceId, "guess") } returns CompletableFuture.failedFuture(DirectorException(403, "Forbidden"))
        val vm = viewModel()
        vm.state.first { it.identity?.deviceId != null }

        vm.remove(removalCode(userId), "guess")
        val removal = vm.state.first { it.removal?.error != null }.removal!!
        assertEquals("wrong passphrase", removal.error)
        assertTrue(removal.needsPassphrase)
        assertEquals(1, vault.identities.value.size)
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
}
