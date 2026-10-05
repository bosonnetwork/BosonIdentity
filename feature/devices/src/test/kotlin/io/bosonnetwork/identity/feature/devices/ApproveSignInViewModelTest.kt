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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.SignInRequest
import io.bosonnetwork.director.client.exceptions.DirectorException
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
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Signing a web page in, over a real vault (with a stand-in for the Keystore) and a mocked node. */
@OptIn(ExperimentalCoroutinesApi::class)
class ApproveSignInViewModelTest {

    private class NoKeystore : KeyProtector {
        override fun isAvailable() = true
        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt) =
            Sealed(ByteArray(12), plaintext.copyOf())
        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt) = sealed.data.copyOf()
        override fun delete(alias: String) = Unit
    }

    private val node = NodeAddress("https://node.example", "nodeid")
    private val dir: File = Files.createTempDirectory("signin").toFile()
    private val vault = IdentityVault(VaultStore(File(dir, "vault")), NoKeystore())
    private val client = mockk<DirectorClient>()
    private val nodes = mockk<NodeConnections>()
    private val prompts = mutableListOf<AuthPrompt>()
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.dev_signin_admin_refused) } returns "admin refused"
    }
    private val viewModels = mutableListOf<ApproveSignInViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { nodes.deviceClient(any(), any()) } returns client
        coEvery { nodes.asUser<Any?>(any(), any(), capture(prompts), any(), any()) } coAnswers {
            arg<suspend (DirectorClient, Signature.KeyPair?) -> Any?>(4).invoke(client, null)
        }
        every { client.getSignInRequest("req7") } returns CompletableFuture.completedFuture(Optional.of(request()))
        every { client.approveSignInRequest("req7", any()) } returns CompletableFuture.completedFuture(null)
        every { client.denySignInRequest("req7") } returns CompletableFuture.completedFuture(null)
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private suspend fun TestScope.viewModel(backupVerified: Boolean = true): ApproveSignInViewModel {
        val userId = vault.add(Signature.KeyPair.random(), null, backupVerified, keepUserKey = true, prompt = AuthPrompt("Confirm")).userId
        vault.update(userId) { it.copy(homeNode = node, deviceId = Id.random().toString()) }
        val vm = ApproveSignInViewModel(
            SavedStateHandle(mapOf(ApproveDeviceViewModel.ARG_USER_ID to userId, ApproveSignInViewModel.ARG_REQUEST_ID to "req7")),
            vault, nodes, context,
        )
        vm.clock = { testScheduler.currentTime }
        viewModels += vm
        return vm
    }

    // The request as the client would decode it; its constructor is the one Jackson uses.
    private fun request(app: String = SignInRequest.APP_PORTAL, lifetime: Long = 120_000L): SignInRequest =
        SignInRequest::class.java.getDeclaredConstructor(
            String::class.java, String::class.java, List::class.java, String::class.java, Long::class.java, Long::class.java,
        ).apply { isAccessible = true }.newInstance("req7", app, listOf(17, 42, 85), "203.0.113.9", 5_000L, 5_000L + lifetime)

    @Test
    fun `the review shows the page, where it asked from, and the numbers`() = runTest {
        val vm = viewModel()
        val state = vm.state.first { it.step == SignInStep.REVIEW }
        assertEquals(listOf(17, 42, 85), state.choices)
        assertEquals("203.0.113.9", state.requestedFrom)
        assertEquals(120L, state.remainingSeconds)
        assertFalse("no number picked yet", state.canApprove)
    }

    @Test
    fun `approving sends the picked number through the user key`() = runTest {
        val vm = viewModel()
        vm.state.first { it.step == SignInStep.REVIEW }
        vm.onPick(42)
        vm.approve()
        assertEquals(SignInStep.DONE, vm.state.first { it.step == SignInStep.DONE }.step)
        verify { client.approveSignInRequest("req7", 42) }
        assertEquals(1, prompts.size)
    }

    @Test
    fun `a wrong number ends in a refusal the user is told about`() = runTest {
        every { client.approveSignInRequest("req7", 17) } returns
            CompletableFuture.failedFuture(DirectorException.fromResponse(409, "not the number", null))
        val vm = viewModel()
        vm.state.first { it.step == SignInStep.REVIEW }
        vm.onPick(17)
        vm.approve()
        val state = vm.state.first { it.step == SignInStep.REFUSED }
        assertTrue(state.wrongNumber)
    }

    @Test
    fun `refusing needs no user key`() = runTest {
        val vm = viewModel()
        vm.state.first { it.step == SignInStep.REVIEW }
        vm.refuse()
        vm.state.first { it.step == SignInStep.REFUSED }
        verify { client.denySignInRequest("req7") }
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `nothing is approved before the backup is checked`() = runTest {
        val vm = viewModel(backupVerified = false)
        vm.state.first { it.step == SignInStep.REVIEW }
        vm.onPick(42)
        assertFalse(vm.state.value.canApprove)
        vm.approve()
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an answered or expired request is gone`() = runTest {
        every { client.getSignInRequest("req7") } returns CompletableFuture.completedFuture(Optional.empty())
        val vm = viewModel()
        assertEquals(SignInStep.EXPIRED, vm.state.first { it.step == SignInStep.EXPIRED }.step)
    }

    @Test
    fun `the countdown runs out on this phone's clock`() = runTest {
        every { client.getSignInRequest("req7") } returns CompletableFuture.completedFuture(Optional.of(request(lifetime = 3_000L)))
        val vm = viewModel()
        vm.state.first { it.step == SignInStep.REVIEW }
        vm.onPick(42)
        val expired = vm.state.first { it.expired }
        assertEquals(0L, expired.remainingSeconds)
        assertFalse(expired.canApprove)
    }

    @Test
    fun `an admin sign-in refused for a non-administrator says why`() = runTest {
        every { client.getSignInRequest("req7") } returns CompletableFuture.completedFuture(Optional.of(request(app = SignInRequest.APP_ADMIN)))
        every { client.approveSignInRequest("req7", 42) } returns
            CompletableFuture.failedFuture(DirectorException.fromResponse(403, "no", null))
        val vm = viewModel()
        vm.state.first { it.step == SignInStep.REVIEW }
        vm.onPick(42)
        vm.approve()
        assertEquals("admin refused", vm.state.first { it.error != null }.error)
    }
}
