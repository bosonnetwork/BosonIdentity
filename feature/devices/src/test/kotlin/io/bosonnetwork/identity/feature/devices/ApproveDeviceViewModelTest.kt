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
import io.bosonnetwork.director.client.Device
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.PairingCode
import io.bosonnetwork.director.client.PendingDevice
import io.bosonnetwork.director.client.Profile
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
import java.util.Base64
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Approving a new device, over a real vault (with a stand-in for the Keystore) and a mocked node.
 * The countdown runs on the test's virtual clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApproveDeviceViewModelTest {

    // Seals nothing: approving never opens a key itself; the node connections are mocked.
    private class NoKeystore : KeyProtector {
        override fun isAvailable() = true
        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt) =
            Sealed(ByteArray(12), plaintext.copyOf())
        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt) = sealed.data.copyOf()
        override fun delete(alias: String) = Unit
    }

    private val node = NodeAddress("https://node.example", "nodeid")
    private val dir: File = Files.createTempDirectory("approve").toFile()
    private val vault = IdentityVault(VaultStore(File(dir, "vault")), NoKeystore())
    private val client = mockk<DirectorClient>()
    private val nodes = mockk<NodeConnections>()
    private val prompts = mutableListOf<AuthPrompt>()
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.dev_wrong_passphrase) } returns "wrong passphrase"
        every { getString(R.string.dev_approve_invalid_code) } returns "not a pairing code"
        every { getString(R.string.dev_scan_invalid_code) } returns "not a code the app uses"
        every { getString(R.string.dev_scan_needs_node) } returns "join a node first"
        every { getString(R.string.dev_approve_admin_refused) } returns "admin refused"
        every { getString(R.string.dev_approve_prompt_title) } returns "Approve a new device"
        every { getString(R.string.dev_approve_prompt_subtitle, any(), any()) } answers {
            val args = secondArg<Array<Any?>>()
            "${args[0]} will join your identity at ${args[1]}"
        }
        every { getString(io.bosonnetwork.identity.core.designsystem.R.string.ds_error_passphrase_required) } returns "passphrase needed"
    }
    private val viewModels = mutableListOf<ApproveDeviceViewModel>()

    private val code = "boson:pair:1:reg42:" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val pairing = PairingCode.parse(code)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { nodes.deviceClient(any(), any()) } returns client
        coEvery { nodes.asUser<Any?>(any(), any(), capture(prompts), any(), any()) } coAnswers {
            arg<suspend (DirectorClient, Signature.KeyPair?) -> Any?>(4).invoke(client, null)
        }
        every { client.profile } returns CompletableFuture.completedFuture(profile())
        every { client.getDeviceRegistration(pairing) } returns CompletableFuture.completedFuture(Optional.of(pending()))
        every { client.approveDeviceRegistration(pairing, any(), any()) } returns CompletableFuture.completedFuture(null)
        every { client.denyDeviceRegistration(pairing) } returns CompletableFuture.completedFuture(null)
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private suspend fun TestScope.viewModel(
        backupVerified: Boolean = true,
        general: Boolean = false,
        homeNode: Boolean = true,
        // An app's request: its code, from the app named "Photon".
        appCode: String? = null,
    ): ApproveDeviceViewModel {
        val userId = vault.add(Signature.KeyPair.random(), null, backupVerified, keepUserKey = true, prompt = AuthPrompt("Confirm")).userId
        if (homeNode) vault.update(userId) { it.copy(homeNode = node, deviceId = Id.random().toString()) }
        val args = mutableMapOf<String, Any?>(ApproveDeviceViewModel.ARG_USER_ID to userId, ApproveDeviceViewModel.ARG_GENERAL to general)
        if (appCode != null) {
            args[ApproveDeviceViewModel.ARG_CODE] = appCode
            args[ApproveDeviceViewModel.ARG_REQUESTER_PACKAGE] = "io.bosonnetwork.photon"
            args[ApproveDeviceViewModel.ARG_REQUESTER_LABEL] = "Photon"
        }
        val vm = ApproveDeviceViewModel(SavedStateHandle(args), vault, nodes, context)
        vm.clock = { testScheduler.currentTime }
        viewModels += vm
        vm.state.first { it.identity != null && (!homeNode || it.identity?.homeNode != null) }
        return vm
    }

    // The request as the client would decode it; its constructor is the one Jackson uses.
    private fun pending(wantsUserKey: Boolean = false, lifetime: Long = 10 * 60_000L): PendingDevice =
        PendingDevice::class.java.getDeclaredConstructor(
            Id::class.java, String::class.java, String::class.java, String::class.java, Boolean::class.java,
            ByteArray::class.java, String::class.java, Long::class.java, Long::class.java,
        ).apply { isAccessible = true }.newInstance(
            Id.random(), "MacBook", "Photon", Device.KIND_APP, wantsUserKey, ByteArray(24), "198.51.100.4",
            // The node's clock is far from this phone's: only the lifetime counts.
            1_000_000L, 1_000_000L + lifetime,
        )

    private fun profile(admin: Boolean = false, passphrase: Boolean = false): Profile =
        Profile::class.java.getDeclaredConstructor(
            Id::class.java, Boolean::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            Long::class.java, Long::class.java, String::class.java, Boolean::class.java,
        ).apply { isAccessible = true }.newInstance(Id.random(), admin, "Alice", null, null, null, 0L, 0L, "Free", passphrase)

    private fun failed(status: Int): CompletableFuture<Void> =
        CompletableFuture.failedFuture(DirectorException.fromResponse(status, "refused", null))

    private suspend fun ApproveDeviceViewModel.review(): ApproveUiState {
        onScanned(code)
        return state.first { it.step == ApproveStep.REVIEW }
    }

    @Test
    fun `the request is shown as the node has it, read with this phone's key`() = runTest {
        val vm = viewModel()
        val state = vm.review()
        val pending = state.pending!!
        assertEquals("MacBook", pending.name)
        assertEquals("Photon", pending.app)
        assertEquals("198.51.100.4", pending.requestedFrom)
        assertFalse(pending.wantsUserKey)
        assertEquals(600, state.remainingSeconds)
        assertFalse(state.canAdmin)
        assertTrue(state.canApprove)
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `text that is not a pairing code stays on the scanner, with a clear error`() = runTest {
        val vm = viewModel()
        vm.onScanned("https://example.com")
        assertEquals(ApproveStep.SCAN, vm.state.value.step)
        assertEquals("not a pairing code", vm.state.value.error)
        vm.onPastedChange("boson:pair:1:nope")
        vm.submitPasted()
        assertEquals(ApproveStep.SCAN, vm.state.value.step)
        coVerify(exactly = 0) { nodes.deviceClient(any(), any()) }
    }

    @Test
    fun `a web page's sign-in code is handed to the sign-in screen, and scanning goes on after`() = runTest {
        val vm = viewModel()
        vm.onScanned("boson:signin:1:req123")
        assertEquals("req123", vm.state.value.signInRequestId)
        assertEquals(ApproveStep.SCAN, vm.state.value.step)
        coVerify(exactly = 0) { nodes.deviceClient(any(), any()) }

        vm.signInHandled()
        assertNull(vm.state.value.signInRequestId)
        // The same code, scanned again when the user comes back, is handed over again.
        vm.onScanned("boson:signin:1:req123")
        assertEquals("req123", vm.state.value.signInRequestId)
    }

    @Test
    fun `a node console's enrollment code is handed over, with or without a home node`() = runTest {
        val enroll = "boson:enroll:1:" + Id.random() + ":" + Id.random() + ":https://node.example"
        val vm = viewModel(general = true, homeNode = false)
        vm.onScanned(enroll)
        assertEquals(enroll, vm.state.value.enrollCode)
        assertEquals(ApproveStep.SCAN, vm.state.value.step)
        coVerify(exactly = 0) { nodes.deviceClient(any(), any()) }
        vm.signInHandled()
        assertNull(vm.state.value.enrollCode)

        // Every other code is answered at the home node, which this identity has none of yet.
        vm.onScanned("boson:signin:1:req123")
        assertNull(vm.state.value.signInRequestId)
        assertEquals("join a node first", vm.state.value.error)
        vm.onScanned(code)
        assertEquals(ApproveStep.SCAN, vm.state.value.step)
        assertEquals("join a node first", vm.state.value.error)
    }

    @Test
    fun `as the app's scanner it reads both codes, and names an unknown one generally`() = runTest {
        val vm = viewModel(general = true)
        assertTrue(vm.state.value.general)
        vm.onScanned("https://example.com")
        assertEquals("not a code the app uses", vm.state.value.error)

        vm.onScanned("boson:signin:1:req456")
        assertEquals("req456", vm.state.value.signInRequestId)
        vm.signInHandled()

        vm.onScanned(code)
        assertEquals(ApproveStep.REVIEW, vm.state.first { it.step == ApproveStep.REVIEW }.step)
        // Starting over keeps it the app's scanner.
        vm.scanAgain()
        assertTrue(vm.state.value.general)
    }

    @Test
    fun `an app's code is read at once, and the review names the app`() = runTest {
        val vm = viewModel(appCode = code)
        val state = vm.state.first { it.step == ApproveStep.REVIEW }
        assertEquals(AppRequester("Photon", "io.bosonnetwork.photon"), state.requester)
        assertEquals("MacBook", state.pending?.name)
    }

    @Test
    fun `an app's request answers its one code`() = runTest {
        every { client.getDeviceRegistration(pairing) } returns CompletableFuture.completedFuture(Optional.empty())
        val vm = viewModel(appCode = code)
        vm.state.first { it.step == ApproveStep.EXPIRED }
        vm.scanAgain()
        assertEquals(ApproveStep.EXPIRED, vm.state.value.step)
        assertEquals("Photon", vm.state.value.requester?.label)
    }

    @Test
    fun `an app can only ask to approve a device`() = runTest {
        val vm = viewModel(appCode = "boson:signin:1:req123")
        val state = vm.state.first { it.error != null }
        assertNull(state.signInRequestId)
        assertEquals(ApproveStep.SCAN, state.step)
        coVerify(exactly = 0) { nodes.deviceClient(any(), any()) }
    }

    @Test
    fun `a pasted code works like a scanned one`() = runTest {
        val vm = viewModel()
        vm.onPastedChange("  $code  ")
        vm.submitPasted()
        assertEquals(ApproveStep.REVIEW, vm.state.first { it.step == ApproveStep.REVIEW }.step)
    }

    @Test
    fun `a request already answered or expired says so`() = runTest {
        every { client.getDeviceRegistration(pairing) } returns CompletableFuture.completedFuture(Optional.empty())
        val vm = viewModel()
        vm.onScanned(code)
        vm.state.first { it.step == ApproveStep.EXPIRED }
    }

    @Test
    fun `approving confirms with the user key, saying who, what and where`() = runTest {
        val vm = viewModel()
        vm.review()
        vm.approve()
        vm.state.first { it.step == ApproveStep.DONE }
        verify { client.approveDeviceRegistration(pairing, null, false) }
        assertEquals("Approve a new device", prompts.single().title)
        assertEquals("MacBook will join your identity at node.example", prompts.single().subtitle)
    }

    @Test
    fun `a request for the identity key needs an explicit acknowledgement`() = runTest {
        every { client.getDeviceRegistration(pairing) } returns CompletableFuture.completedFuture(Optional.of(pending(wantsUserKey = true)))
        val vm = viewModel()
        val state = vm.review()
        assertTrue(state.pending!!.wantsUserKey)
        assertFalse(state.canApprove)

        vm.approve()
        assertEquals(ApproveStep.REVIEW, vm.state.value.step)
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }

        vm.onUnderstoodChange(true)
        vm.approve()
        vm.state.first { it.step == ApproveStep.DONE }
        verify { client.approveDeviceRegistration(pairing, null, false) }
    }

    @Test
    fun `the admin flag is sent only when chosen by an admin`() = runTest {
        every { client.profile } returns CompletableFuture.completedFuture(profile(admin = true))
        val vm = viewModel()
        assertTrue(vm.review().canAdmin)
        vm.onAdminChange(true)
        vm.approve()
        vm.state.first { it.step == ApproveStep.DONE }
        verify { client.approveDeviceRegistration(pairing, null, true) }
    }

    @Test
    fun `admin stays off by default, and a non-admin can't choose it`() = runTest {
        val first = viewModel()
        first.review()
        first.onAdminChange(true)
        assertFalse(first.state.value.admin)
        first.approve()
        first.state.first { it.step == ApproveStep.DONE }

        every { client.profile } returns CompletableFuture.completedFuture(profile(admin = true))
        val second = viewModel()
        second.review()
        second.approve()
        second.state.first { it.step == ApproveStep.DONE }
        verify(exactly = 2) { client.approveDeviceRegistration(pairing, null, false) }
        verify(exactly = 0) { client.approveDeviceRegistration(pairing, any(), true) }
    }

    @Test
    fun `an account with a passphrase is asked for it before the screen lock`() = runTest {
        every { client.profile } returns CompletableFuture.completedFuture(profile(passphrase = true))
        val vm = viewModel()
        vm.review()
        vm.approve()
        assertEquals(ApproveStep.PASSPHRASE, vm.state.value.step)
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }

        vm.onPassphraseChange("secret")
        vm.approve()
        vm.state.first { it.step == ApproveStep.DONE }
        verify { client.approveDeviceRegistration(pairing, "secret", false) }
    }

    @Test
    fun `the passphrase the node asks for anyway is asked, then sent`() = runTest {
        every { client.approveDeviceRegistration(pairing, null, false) } returns failed(428)
        val vm = viewModel()
        vm.review()
        vm.approve()
        val asking = vm.state.first { it.step == ApproveStep.PASSPHRASE }
        assertNull(asking.error)

        vm.onPassphraseChange("secret")
        vm.approve()
        vm.state.first { it.step == ApproveStep.DONE }
        verify { client.approveDeviceRegistration(pairing, "secret", false) }
    }

    @Test
    fun `a wrong passphrase is reported as one, and cleared`() = runTest {
        every { client.profile } returns CompletableFuture.completedFuture(profile(passphrase = true))
        every { client.approveDeviceRegistration(pairing, "guess", false) } returns failed(403)
        val vm = viewModel()
        vm.review()
        vm.approve()
        vm.onPassphraseChange("guess")
        vm.approve()
        val state = vm.state.first { it.error != null }
        assertEquals(ApproveStep.PASSPHRASE, state.step)
        assertEquals("wrong passphrase", state.error)
        assertEquals("", state.passphrase)
    }

    @Test
    fun `an admin approval the node refuses says why`() = runTest {
        every { client.profile } returns CompletableFuture.completedFuture(profile(admin = true))
        every { client.approveDeviceRegistration(pairing, null, true) } returns failed(403)
        val vm = viewModel()
        vm.review()
        vm.onAdminChange(true)
        vm.approve()
        val state = vm.state.first { it.error != null }
        assertEquals(ApproveStep.REVIEW, state.step)
        assertEquals("admin refused", state.error)
    }

    @Test
    fun `when the countdown runs out, approving is refused`() = runTest {
        every { client.getDeviceRegistration(pairing) } returns CompletableFuture.completedFuture(Optional.of(pending(lifetime = 5_000)))
        val vm = viewModel()
        assertEquals(5, vm.review().remainingSeconds)
        val expired = vm.state.first { it.expired }
        assertEquals(0, expired.remainingSeconds)
        assertFalse(expired.canApprove)
        vm.approve()
        assertEquals(ApproveStep.REVIEW, vm.state.value.step)
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a request gone by the time it is approved says so`() = runTest {
        every { client.approveDeviceRegistration(pairing, null, false) } returns failed(404)
        val vm = viewModel()
        vm.review()
        vm.approve()
        vm.state.first { it.step == ApproveStep.EXPIRED }
    }

    @Test
    fun `without a checked backup, approving is refused, denying is not`() = runTest {
        val vm = viewModel(backupVerified = false)
        assertFalse(vm.review().canApprove)
        vm.approve()
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }

        vm.deny()
        vm.state.first { it.step == ApproveStep.DENIED }
    }

    @Test
    fun `denying goes through this phone's key`() = runTest {
        val vm = viewModel()
        vm.review()
        vm.deny()
        vm.state.first { it.step == ApproveStep.DENIED }
        verify { client.denyDeviceRegistration(pairing) }
        verify(exactly = 0) { client.approveDeviceRegistration(any<PairingCode>(), any(), any()) }
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the countdown reads as minutes and seconds`() {
        assertEquals("4:59", countdown(299))
        assertEquals("0:05", countdown(5))
        assertEquals("10:00", countdown(600))
    }
}
