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
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.exceptions.DirectorException
import io.bosonnetwork.identity.core.boson.AvatarStore
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.ProfileSync
import io.bosonnetwork.identity.core.boson.SyncOutcome
import io.bosonnetwork.identity.core.boson.ThisDevice
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.LocalProfile
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.model.ProfileFields
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.core.vault.KeyProtector
import io.bosonnetwork.identity.core.vault.MoveCode
import io.bosonnetwork.identity.core.vault.MovePayload
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Moving the identity here from the old phone, over a real vault (with a stand-in for the Keystore) and a
 * mocked node: the old phone's QR code is sealed here, as the old phone seals it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveFromOldPhoneViewModelTest {

    // Seals nothing; [failSeals] makes the next seals fail as if the user dismissed the prompt.
    private class FakeKeystore : KeyProtector {
        var failSeals = 0

        override fun isAvailable() = true
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
    private val client = mockk<DirectorClient>()
    private val nodes = mockk<NodeConnections>()
    private val profileSync = mockk<ProfileSync>()
    private val realSync = ProfileSync(vault, nodes, AvatarStore(File(dir, "avatars")))
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.onb_move_code_wrong) } returns "wrong code"
        every { getString(R.string.onb_move_not_a_code) } returns "not a move"
        every { getString(R.string.onb_move_already_here) } returns "already here"
        every { getString(R.string.onb_move_wrong_passphrase) } returns "wrong passphrase"
    }
    private val viewModels = mutableListOf<MoveFromOldPhoneViewModel>()

    private val node = NodeAddress("https://node.example", Id.random().toString())
    private val userKey = Signature.KeyPair.random()
    private val userId = Id.of(userKey.publicKey().bytes()).toString()
    private val code = MoveCode.newCode()
    private val profile = LocalProfile(ProfileFields(name = "Alice", bio = "Moved", email = "alice@example.com"))

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { nodes.identify(any()) } answers { firstArg() }
        coEvery { nodes.asUser<Any?>(any(), any(), any(), true, any()) } coAnswers {
            arg<suspend (DirectorClient, Signature.KeyPair?) -> Any?>(4).invoke(client, Signature.KeyPair.random())
        }
        every { client.registerDevice(any<Signature.KeyPair>(), ThisDevice.name, ThisDevice.APP_NAME, any()) } returns
            CompletableFuture.completedFuture(null)
        coEvery { profileSync.edit(any(), any(), any(), any()) } coAnswers { realSync.edit(arg(0), arg(1), arg(2), arg(3)) }
        coEvery { profileSync.sync(any(), any(), any()) } returns SyncOutcome.Pushed
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun viewModel() = MoveFromOldPhoneViewModel(vault, nodes, profileSync, context).also { viewModels += it }

    private fun qr(homeNode: NodeAddress?) =
        MoveCode.seal(MovePayload(userKey.privateKey().bytes(), homeNode, true, profile), code)

    private suspend fun MoveFromOldPhoneViewModel.moved(): String = state.first { it.finishedUserId != null }.finishedUserId!!

    @Test
    fun `an identity without a home node moves with its profile, no node asked`() = runTest {
        val vm = viewModel()
        vm.onScanned(qr(null))
        assertEquals(MoveStep.CODE, vm.state.value.step)
        vm.onCodeChange(code.lowercase())
        vm.open()

        assertEquals(userId, vm.moved())
        val info = vault.identities.value.single()
        assertEquals("Alice", info.name)
        assertEquals("alice@example.com", info.profile.fields.email)
        assertTrue(info.backupVerified)
        assertNull(info.homeNode)
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `with a home node, this phone joins it by the user key, then the profile syncs`() = runTest {
        val vm = viewModel()
        vm.onScanned(qr(node))
        vm.onCodeChange(code)
        vm.open()

        vm.moved()
        val info = vault.identities.value.single()
        assertEquals(node, info.homeNode)
        assertTrue(info.deviceId != null)
        verify { client.registerDevice(any<Signature.KeyPair>(), ThisDevice.name, ThisDevice.APP_NAME, null) }
        coVerify { profileSync.sync(userId, any(), null) }
    }

    @Test
    fun `a wrong code says so, and the right one still opens it`() = runTest {
        val vm = viewModel()
        vm.onScanned(qr(null))
        vm.onCodeChange(MoveCode.newCode())
        vm.open()
        assertEquals("wrong code", vm.state.first { it.error != null }.error)
        assertEquals(MoveStep.CODE, vm.state.value.step)

        vm.onCodeChange(code)
        vm.open()
        vm.moved()
    }

    @Test
    fun `other QR codes are not taken`() = runTest {
        val vm = viewModel()
        vm.onScanned("boson:pair:1:abc:def")
        assertEquals(MoveStep.SCAN, vm.state.value.step)
        assertEquals("not a move", vm.state.value.error)
    }

    @Test
    fun `an account with a passphrase asks for it before this phone joins`() = runTest {
        every { client.registerDevice(any<Signature.KeyPair>(), any(), any(), null) } returns
            CompletableFuture.failedFuture(DirectorException.fromResponse(428, "passphrase", null))
        every { client.registerDevice(any<Signature.KeyPair>(), any(), any(), "guess") } returns
            CompletableFuture.failedFuture(DirectorException.fromResponse(403, "wrong", null))
        every { client.registerDevice(any<Signature.KeyPair>(), any(), any(), "secret") } returns CompletableFuture.completedFuture(null)
        val vm = viewModel()
        vm.onScanned(qr(node))
        vm.onCodeChange(code)
        vm.open()
        assertEquals(MoveStep.PASSPHRASE, vm.state.first { it.step == MoveStep.PASSPHRASE }.step)

        vm.onPassphraseChange("guess")
        vm.retryJoin()
        assertEquals("wrong passphrase", vm.state.first { it.error != null }.error)

        vm.onPassphraseChange("secret")
        vm.retryJoin()
        vm.moved()
        assertEquals(node, vault.identities.value.single().homeNode)
    }

    @Test
    fun `an unreachable node leaves the identity here, to join later`() = runTest {
        every { client.registerDevice(any<Signature.KeyPair>(), any(), any(), any()) } returns
            CompletableFuture.failedFuture(DirectorException("no answer", java.io.IOException("refused")))
        val vm = viewModel()
        vm.onScanned(qr(node))
        vm.onCodeChange(code)
        vm.open()
        assertEquals(MoveStep.JOIN_FAILED, vm.state.first { it.step == MoveStep.JOIN_FAILED }.step)
        assertEquals("Alice", vault.identities.value.single().name)

        vm.joinLater()
        assertEquals(userId, vm.moved())
        assertNull(vault.identities.value.single().homeNode)
    }

    @Test
    fun `an identity already here is not added twice`() = runTest {
        vault.add(userKey, null, true, keepUserKey = true, prompt = AuthPrompt("Confirm"))
        val vm = viewModel()
        vm.onScanned(qr(null))
        vm.onCodeChange(code)
        vm.open()
        assertEquals("already here", vm.state.first { it.error != null }.error)
        assertEquals(MoveStep.SCAN, vm.state.value.step)
    }

    @Test
    fun `a dismissed screen lock goes back to the code`() = runTest {
        keystore.failSeals = 1
        val vm = viewModel()
        vm.onScanned(qr(null))
        vm.onCodeChange(code)
        vm.open()
        vm.state.first { it.step == MoveStep.CODE && vault.identities.value.isEmpty() }
        vm.open()
        vm.moved()
    }
}
