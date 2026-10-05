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

package io.bosonnetwork.identity.feature.onboarding

import android.content.Context
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.CryptoBox
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.DeviceApproval
import io.bosonnetwork.director.client.DeviceRegistration
import io.bosonnetwork.director.client.DirectorGuest
import io.bosonnetwork.director.client.exceptions.RegistrationDeniedException
import io.bosonnetwork.director.client.exceptions.RegistrationExpiredException
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.ThisDevice
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Moving the identity here from the old phone, over a real vault (with a stand-in for the Keystore)
 * and a mocked node; the old phone's approval is the node's answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveFromOldPhoneViewModelTest {

    // Seals nothing; [failSeals] makes the next seals fail as if the user dismissed the prompt.
    private class FakeKeystore : KeyProtector {
        var available = true
        var failSeals = 0

        override fun isAvailable() = available
        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt): Sealed {
            if (failSeals > 0) {
                failSeals--
                throw AppError.AuthenticationCancelled("dismissed")
            }
            return Sealed(ByteArray(12), plaintext.copyOf())
        }
        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt) = sealed.data.copyOf()
        override fun delete(alias: String) = Unit
    }

    private val dir: File = Files.createTempDirectory("move").toFile()
    private val keystore = FakeKeystore()
    private val vault = IdentityVault(VaultStore(File(dir, "vault")), keystore)
    private val guest = mockk<DirectorGuest>()
    private val nodes = mockk<NodeConnections>()
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.onb_join_invalid_qr) } returns "not a node"
        every { getString(R.string.onb_protect_no_lock) } returns "no screen lock"
        every { getString(io.bosonnetwork.identity.core.designsystem.R.string.ds_error_cancelled) } returns "cancelled"
    }
    private val viewModels = mutableListOf<MoveFromOldPhoneViewModel>()

    private val node = NodeAddress("https://node.example", Id.random().toString())
    private val userKey = Signature.KeyPair.random()
    private val userId = Id.of(userKey.publicKey().bytes())

    // The device keys this phone asked to join with, in order, and the answer the old phone gives each.
    private val requested = mutableListOf<Signature.KeyPair>()
    private val registrations = mutableListOf<DeviceRegistration>()
    private val answers = ArrayDeque<CompletableFuture<DeviceApproval>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { nodes.identify(any()) } answers { firstArg<NodeAddress>().copy(nodeId = node.nodeId) }
        coEvery { nodes.guest(any()) } returns guest
        every { guest.requestDeviceRegistration(any(), ThisDevice.name, ThisDevice.APP_NAME, true) } answers {
            val key = firstArg<Signature.KeyPair>()
            // Kept as the bytes: the view model destroys the key once it is stored.
            requested += Signature.KeyPair.fromPrivateKey(key.privateKey().bytes())
            CompletableFuture.completedFuture(registration(key).also { registrations += it })
        }
        every { guest.finishDeviceRegistration(any()) } answers { answers.removeFirst() }
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun viewModel() = MoveFromOldPhoneViewModel(vault, nodes, context).also { viewModels += it }

    // The client's models, as it builds them: their constructors are package-private.
    private fun registration(key: Signature.KeyPair): DeviceRegistration =
        DeviceRegistration::class.java.getDeclaredConstructor(
            String::class.java, Signature.KeyPair::class.java, CryptoBox.KeyPair::class.java, Boolean::class.java,
        ).apply { isAccessible = true }.newInstance("reg-" + requested.size, key, CryptoBox.KeyPair.random(), true)

    private fun approval(key: Signature.KeyPair? = Signature.KeyPair.fromPrivateKey(userKey.privateKey().bytes())): DeviceApproval =
        DeviceApproval::class.java.getDeclaredConstructor(Id::class.java, Signature.KeyPair::class.java)
            .apply { isAccessible = true }.newInstance(userId, key)

    private fun approved() = CompletableFuture.completedFuture(approval())

    private fun MoveFromOldPhoneViewModel.chooseNode() {
        onAddressChange("node.example")
        onNodeIdChange(node.nodeId!!)
        check()
    }

    @Test
    fun `the approved identity is stored with the device key the node now knows`() = runTest {
        val pending = CompletableFuture<DeviceApproval>()
        answers += pending
        val vm = viewModel()
        vm.chooseNode()

        // The pairing code is shown while the old phone decides.
        val pairing = vm.state.first { it.step == MoveStep.PAIRING }
        assertEquals(registrations.single().pairingCode.toString(), pairing.pairingCode)
        assertTrue(vault.identities.value.isEmpty())

        pending.complete(approval())
        val done = vm.state.first { it.finishedUserId != null }
        assertEquals(userId.toString(), done.finishedUserId)

        val deviceKey = requested.single()
        val info = vault.identities.value.single()
        assertEquals(userId.toString(), info.userId)
        assertEquals(node, info.homeNode)
        assertEquals(Id.of(deviceKey.publicKey().bytes()).toString(), info.deviceId)
        // This phone hasn't seen the backup checked.
        assertFalse(info.backupVerified)
        assertTrue(vault.keepsUserKey(userId.toString()))
        assertArrayEquals(deviceKey.publicKey().bytes(), vault.deviceKey(userId.toString(), AuthPrompt("x")).publicKey().bytes())
        vault.withUserKey(userId.toString(), AuthPrompt("x")) { assertArrayEquals(userKey.publicKey().bytes(), it.publicKey().bytes()) }
        coVerify { nodes.identify(NodeAddress("https://node.example", node.nodeId)) }
    }

    @Test
    fun `a node's QR code chooses the node`() = runTest {
        answers += approved()
        val vm = viewModel()
        vm.scan()
        vm.onScanned("{\"url\":\"https://node.example\",\"id\":\"${node.nodeId}\"}")
        assertEquals(userId.toString(), vm.state.first { it.finishedUserId != null }.finishedUserId)

        val other = viewModel()
        other.scan()
        other.onScanned("https://example.com")
        assertEquals("not a node", other.state.value.error)
        assertEquals(MoveStep.ADDRESS, other.state.value.step)
    }

    @Test
    fun `a denied request can be tried again, with a new key`() = runTest {
        answers += CompletableFuture.failedFuture(RegistrationDeniedException("denied"))
        answers += approved()
        val vm = viewModel()
        vm.chooseNode()
        val denied = vm.state.first { it.step == MoveStep.DENIED }
        assertNull(denied.pairingCode)
        assertTrue(vault.identities.value.isEmpty())

        vm.tryAgain()
        vm.state.first { it.finishedUserId != null }
        assertEquals(2, requested.size)
        assertNotEquals(Id.of(requested[0].publicKey().bytes()), Id.of(requested[1].publicKey().bytes()))
        assertEquals(Id.of(requested[1].publicKey().bytes()).toString(), vault.identities.value.single().deviceId)
    }

    @Test
    fun `an expired request says so`() = runTest {
        answers += CompletableFuture.failedFuture(RegistrationExpiredException("expired"))
        val vm = viewModel()
        vm.chooseNode()
        vm.state.first { it.step == MoveStep.EXPIRED }
        assertTrue(vault.identities.value.isEmpty())
    }

    @Test
    fun `an approval without the identity's key stores nothing`() = runTest {
        answers += CompletableFuture.completedFuture(approval(key = null))
        val vm = viewModel()
        vm.chooseNode()
        val state = vm.state.first { it.step == MoveStep.EXPIRED }
        assertTrue(state.error != null)
        assertTrue(vault.identities.value.isEmpty())
    }

    @Test
    fun `an identity the phone couldn't store is stored on retry, without asking again`() = runTest {
        answers += approved()
        keystore.failSeals = 1
        val vm = viewModel()
        vm.chooseNode()
        assertEquals("cancelled", vm.state.first { it.step == MoveStep.STORE_FAILED }.error)
        assertTrue(vault.identities.value.isEmpty())

        vm.tryAgain()
        vm.state.first { it.finishedUserId != null }
        assertEquals(Id.of(requested.single().publicKey().bytes()).toString(), vault.identities.value.single().deviceId)
        verify(exactly = 1) { guest.requestDeviceRegistration(any(), any(), any(), any()) }
    }

    @Test
    fun `without a screen lock, the old phone isn't asked`() = runTest {
        keystore.available = false
        val vm = viewModel()
        vm.chooseNode()
        assertEquals("no screen lock", vm.state.value.error)
        coVerify(exactly = 0) { nodes.identify(any()) }
    }
}
