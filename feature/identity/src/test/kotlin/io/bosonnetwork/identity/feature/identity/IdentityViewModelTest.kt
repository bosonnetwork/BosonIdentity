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
import io.bosonnetwork.director.client.Profile
import io.bosonnetwork.director.client.exceptions.DirectorException
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.NodeConnections
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
import org.junit.Before
import org.junit.Test

/** The home screen follows the identity picked in the switcher. */
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

    private val node = NodeAddress("https://node.example", "nodeid")
    private val dir: File = Files.createTempDirectory("identity").toFile()
    private val vault = IdentityVault(VaultStore(File(dir, "vault")), NoKeystore())
    private val storeJob = Job()
    private val active = ActiveIdentity(
        vault,
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob)) { File(dir, "active.preferences_pb") },
    )
    private val nodes = mockk<NodeConnections>()
    private val context = mockk<Context>(relaxed = true)
    private val viewModels = mutableListOf<IdentityViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        storeJob.cancel()
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun viewModel() = IdentityViewModel(vault, active, nodes, context).also { viewModels += it }

    private fun clientFor(name: String): DirectorClient {
        val profile = Profile::class.java.getDeclaredConstructor(
            Id::class.java, Boolean::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            Long::class.java, Long::class.java, String::class.java, Boolean::class.java,
        ).apply { isAccessible = true }.newInstance(Id.random(), false, name, null, null, null, 0L, 0L, "Free", false)
        return mockk {
            every { this@mockk.profile } returns CompletableFuture.completedFuture(profile)
            // An older node: it doesn't report recovery methods.
            every { recoveryMethods } returns CompletableFuture.failedFuture(DirectorException.fromResponse(404, "no", null))
        }
    }

    private suspend fun addIdentity(homeNode: NodeAddress?): String {
        val userId = vault.add(Signature.KeyPair.random(), null, true, keepUserKey = true, prompt = AuthPrompt("Confirm")).userId
        if (homeNode != null) vault.update(userId) { it.copy(homeNode = homeNode) }
        return userId
    }

    @Test
    fun `the identity in use is shown, and switching shows the other`() = runTest {
        val first = addIdentity(node)
        val second = addIdentity(node)
        val third = addIdentity(null)
        coEvery { nodes.deviceClient(match<IdentityInfo> { it.userId == first }, any()) } returns clientFor("Alice")
        coEvery { nodes.deviceClient(match<IdentityInfo> { it.userId == second }, any()) } returns clientFor("Bob")

        val vm = viewModel()
        var state = vm.state.first { it.node is NodeState.Ready }
        assertEquals(first, state.identity?.userId)
        assertEquals("Alice", (state.node as NodeState.Ready).view.name)

        // Same node, other identity: it is asked again, as the other identity.
        active.select(second)
        state = vm.state.first { it.identity?.userId == second && it.node is NodeState.Ready }
        assertEquals("Bob", (state.node as NodeState.Ready).view.name)
        coVerify { nodes.deviceClient(match<IdentityInfo> { it.userId == second }, any()) }
        assertEquals("Bob", vault.identities.first { ids -> ids.any { it.name == "Bob" } }.single { it.userId == second }.name)

        active.select(third)
        state = vm.state.first { it.identity?.userId == third }
        assertEquals(NodeState.None, state.node)
    }
}
