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
import io.bosonnetwork.director.client.EnrollmentCode
import io.bosonnetwork.director.client.PendingEnrollment
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.ProfileSync
import io.bosonnetwork.identity.core.boson.SyncOutcome
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
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Enrolling as a node's administrator, over a real vault (with a stand-in for the Keystore) and a mocked node. */
@OptIn(ExperimentalCoroutinesApi::class)
class EnrollAdminViewModelTest {

    private class NoKeystore : KeyProtector {
        override fun isAvailable() = true
        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt) =
            Sealed(ByteArray(12), plaintext.copyOf())
        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt) = sealed.data.copyOf()
        override fun delete(alias: String) = Unit
    }

    private val nodeId = Id.random()
    private val code = EnrollmentCode.of(Id.random(), nodeId, "https://node.example")
    private val codeNode = NodeAddress("https://node.example", nodeId.toString())
    private val deviceId = Id.random()
    private val dir: File = Files.createTempDirectory("enroll").toFile()
    private val vault = IdentityVault(VaultStore(File(dir, "vault")), NoKeystore())
    private val nodes = mockk<NodeConnections>()
    private val profileSync = mockk<ProfileSync>()
    private val prompts = mutableListOf<AuthPrompt>()
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.dev_enroll_deactivated) } returns "deactivated"
    }
    private val viewModels = mutableListOf<EnrollAdminViewModel>()
    // What the console answers; completed by each test.
    private var answer = CompletableDeferred<Unit>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { nodes.claimEnrollment(any(), code, capture(prompts)) } returns pending(47)
        coEvery { nodes.finishEnrollment(any()) } coAnswers { answer.await() }
        coEvery { profileSync.sync(any(), any(), any()) } returns SyncOutcome.Pushed
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private suspend fun TestScope.viewModel(backupVerified: Boolean = true, homeNode: NodeAddress? = null): Pair<String, EnrollAdminViewModel> {
        val userId = vault.add(Signature.KeyPair.random(), null, backupVerified, keepUserKey = true, prompt = AuthPrompt("Confirm")).userId
        if (homeNode != null) vault.update(userId) { it.copy(homeNode = homeNode, deviceId = Id.random().toString()) }
        val vm = EnrollAdminViewModel(
            SavedStateHandle(mapOf(ApproveDeviceViewModel.ARG_USER_ID to userId, EnrollAdminViewModel.ARG_CODE to code.toString())),
            vault, nodes, profileSync, context,
        )
        vm.clock = { testScheduler.currentTime }
        viewModels += vm
        vm.state.first { it.identity != null }
        return userId to vm
    }

    // The claim as the client makes it; its constructor is package-private.
    private fun pending(number: Int): PendingEnrollment =
        PendingEnrollment::class.java.getDeclaredConstructor(
            EnrollmentCode::class.java, Id::class.java, Int::class.java, Long::class.java,
        ).apply { isAccessible = true }.newInstance(code, deviceId, number, System.currentTimeMillis() + 600_000L)

    @Test
    fun `an identity without a home node joins the code's node as its administrator`() = runTest {
        val (userId, vm) = viewModel()
        assertEquals(EnrollStep.REVIEW, vm.state.value.step)
        assertTrue(vm.state.value.canClaim)
        assertEquals(codeNode, vm.state.value.node)

        vm.claim()
        val waiting = vm.state.first { it.step == EnrollStep.NUMBER }
        assertEquals(47, waiting.number)
        assertTrue(waiting.remainingSeconds in 590..600)
        assertEquals(1, prompts.size)
        coVerify { nodes.claimEnrollment(userId, code, any()) }

        answer.complete(Unit)
        vm.state.first { it.step == EnrollStep.DONE }
        val identity = vault.identities.value.first { it.userId == userId }
        assertEquals(codeNode, identity.homeNode)
        assertEquals(deviceId.toString(), identity.deviceId)
        assertTrue(identity.administers)
        // The new account starts empty on the node: the profile on this phone fills it.
        coVerify { profileSync.sync(userId, any(), any()) }
    }

    @Test
    fun `a member of the node keeps its home node, and administers it`() = runTest {
        val home = NodeAddress("https://node.example", nodeId.toString())
        val (userId, vm) = viewModel(homeNode = home)
        assertEquals(EnrollStep.REVIEW, vm.state.value.step)
        vm.claim()
        answer.complete(Unit)
        vm.state.first { it.step == EnrollStep.DONE }
        val identity = vault.identities.value.first { it.userId == userId }
        assertEquals(home, identity.homeNode)
        assertTrue(identity.administers)
    }

    @Test
    fun `an identity that lives on another node is refused`() = runTest {
        val (_, vm) = viewModel(homeNode = NodeAddress("https://other.example", Id.random().toString()))
        assertEquals(EnrollStep.OTHER_NODE, vm.state.first { it.step == EnrollStep.OTHER_NODE }.step)
        vm.claim()
        coVerify(exactly = 0) { nodes.claimEnrollment(any(), any(), any()) }
    }

    @Test
    fun `without a checked backup, claiming is refused`() = runTest {
        val (_, vm) = viewModel(backupVerified = false)
        assertFalse(vm.state.value.canClaim)
        vm.claim()
        assertEquals(EnrollStep.REVIEW, vm.state.value.step)
        coVerify(exactly = 0) { nodes.claimEnrollment(any(), any(), any()) }
    }

    @Test
    fun `a code another phone claimed first says so`() = runTest {
        coEvery { nodes.claimEnrollment(any(), code, any()) } throws AppError.Conflict("claimed")
        val (_, vm) = viewModel()
        vm.claim()
        assertEquals(EnrollStep.TAKEN, vm.state.value.step)
    }

    @Test
    fun `a code that ended or never was says so`() = runTest {
        coEvery { nodes.claimEnrollment(any(), code, any()) } throws AppError.NotFound("gone")
        val (_, vm) = viewModel()
        vm.claim()
        assertEquals(EnrollStep.EXPIRED, vm.state.value.step)
    }

    @Test
    fun `a cancelled screen lock goes back to the review`() = runTest {
        coEvery { nodes.claimEnrollment(any(), code, any()) } throws AppError.AuthenticationCancelled()
        val (_, vm) = viewModel()
        vm.claim()
        assertEquals(EnrollStep.REVIEW, vm.state.value.step)
    }

    @Test
    fun `a denial on the console leaves the identity as it was`() = runTest {
        val (userId, vm) = viewModel()
        vm.claim()
        answer.completeExceptionally(AppError.Conflict("denied"))
        assertEquals(EnrollStep.DENIED, vm.state.first { it.step == EnrollStep.DENIED }.step)
        val identity = vault.identities.value.first { it.userId == userId }
        assertNull(identity.homeNode)
        assertFalse(identity.administers)
    }

    @Test
    fun `an expired request says so`() = runTest {
        val (_, vm) = viewModel()
        vm.claim()
        answer.completeExceptionally(AppError.Timeout("expired"))
        assertEquals(EnrollStep.EXPIRED, vm.state.first { it.step == EnrollStep.EXPIRED }.step)
    }

    @Test
    fun `a wait the network cut is resumed with the same claim`() = runTest {
        val (userId, vm) = viewModel()
        vm.claim()
        answer.completeExceptionally(AppError.Network("offline"))
        assertEquals(EnrollStep.ERROR, vm.state.first { it.step == EnrollStep.ERROR }.step)

        answer = CompletableDeferred()
        vm.retry()
        assertEquals(EnrollStep.NUMBER, vm.state.value.step)
        assertEquals(47, vm.state.value.number)
        answer.complete(Unit)
        vm.state.first { it.step == EnrollStep.DONE }
        coVerify(exactly = 1) { nodes.claimEnrollment(any(), any(), any()) }
        assertTrue(vault.identities.value.first { it.userId == userId }.administers)
    }
}
