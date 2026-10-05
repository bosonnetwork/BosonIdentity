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

package io.bosonnetwork.identity.feature.security

import android.content.Context
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.Device
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.ThisDevice
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Security tab's overview, unlinking and renewing this phone's key, over a real vault (with a
 * stand-in for the Keystore), a real DataStore for the identity in use, and a mocked node.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {

    private val test = TestVault()
    private val vault = test.vault
    private val node = TestVault.NODE
    private val client = mockk<DirectorClient>()
    private val nodes = mockk<NodeConnections>()
    private val prompts = mutableListOf<AuthPrompt>()
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.sec_wrong_passphrase) } returns "wrong passphrase"
        every { getString(io.bosonnetwork.identity.core.designsystem.R.string.ds_error_passphrase_required) } returns "passphrase needed"
        every { getString(io.bosonnetwork.identity.core.designsystem.R.string.ds_error_cancelled) } returns "cancelled"
    }
    private val viewModels = mutableListOf<SecurityViewModel>()

    private val oldDeviceId = Id.random()
    private val github = linked(provider = "github")
    private val passkey = device(name = "Chrome", kind = Device.KIND_PASSKEY, recovery = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { nodes.deviceClient(any(), any()) } returns client
        coEvery { nodes.asUser<Any?>(any(), any(), capture(prompts), any(), any()) } coAnswers {
            arg<suspend (DirectorClient, Signature.KeyPair?) -> Any?>(4).invoke(client, null)
        }
        coEvery { nodes.forget(any()) } just runs
        every { client.profile } returns done(profile(passphrase = false))
        every { client.listDevices() } returns done(listOf(device(id = oldDeviceId, name = "Pixel 9"), passkey))
        every { client.recoveryMethods } returns done(recoveryMethods(oauth = 1, passkeys = 1, codes = 3))
        every { client.listLinkedIdentities() } returns done(listOf(github))
        every { client.listSecurityEvents() } returns done(
            listOf(event("set", "session", at = 1_000), event("device-added", "app: Pixel 9", at = 3_000, address = "198.51.100.4")),
        )
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        test.close()
    }

    private fun viewModel() = SecurityViewModel(test.active, vault, nodes, context).also { viewModels += it }

    private suspend fun SecurityViewModel.ready(): SecurityOverview =
        (state.first { it.overview is OverviewState.Ready }.overview as OverviewState.Ready).overview

    @Test
    fun `the overview is read with this phone's key`() = runTest {
        test.addIdentity(deviceId = oldDeviceId.toString())
        val overview = viewModel().ready()
        assertFalse(overview.passphraseProtected)
        assertEquals(RecoveryView(accounts = 1, passkeys = 1, recoveryCodes = 3), overview.recovery)
        assertEquals(listOf(LinkedView(github.sessionId.toString(), "GitHub", "Alice", "alice@example.com")), overview.linked)
        // Only the passkeys, not the apps.
        assertEquals(listOf(PasskeyView(passkey.id.toString(), "Chrome", recoveryMethod = true)), overview.passkeys)
        // Newest first.
        assertEquals(listOf(LogKind.DEVICE_ADDED, LogKind.PASSPHRASE_SET), overview.log!!.map { it.kind })
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `what an older node doesn't report is left out, not failed`() = runTest {
        test.addIdentity()
        every { client.recoveryMethods } returns refused(404)
        every { client.listLinkedIdentities() } returns refused(404)
        every { client.listSecurityEvents() } returns refused(404)
        val overview = viewModel().ready()
        assertNull(overview.recovery)
        assertNull(overview.linked)
        assertNull(overview.log)
    }

    @Test
    fun `without a home node there is nothing to ask`() = runTest {
        test.addIdentity(node = null)
        val vm = viewModel()
        assertEquals(OverviewState.None, vm.state.first { it.identity != null }.overview)
        coVerify(exactly = 0) { nodes.deviceClient(any(), any()) }
    }

    @Test
    fun `the security log reads each kind of event`() {
        assertEquals(LogEntry(LogKind.PASSPHRASE_SET, at = 5), logEntry(event("set", "session", at = 5)))
        assertEquals(LogKind.PASSPHRASE_CHANGED, logEntry(event("changed", "passphrase")).kind)
        assertEquals(LogKind.PASSPHRASE_REMOVED, logEntry(event("removed", "passphrase")).kind)
        assertEquals(LogKind.RECOVERY_CODES, logEntry(event("recovery-codes", "passphrase")).kind)
        assertEquals(ResetMethod.RECOVERY_CODE, logEntry(event("reset", "recovery-code")).resetMethod)
        logEntry(event("reset", "oauth:github")).let {
            assertEquals(ResetMethod.ACCOUNT, it.resetMethod)
            assertEquals("GitHub", it.subject)
        }
        assertEquals(ResetMethod.PASSKEY, logEntry(event("reset", "passkey:" + Id.random())).resetMethod)
        assertEquals(ResetMethod.UNKNOWN, logEntry(event("reset", "magic")).resetMethod)
        logEntry(event("device-added", "passkey: MacBook - Chrome", address = "203.0.113.7")).let {
            assertEquals(LogKind.DEVICE_ADDED, it.kind)
            assertTrue(it.passkey)
            assertEquals("MacBook - Chrome", it.subject)
            assertEquals("203.0.113.7", it.address)
        }
        logEntry(event("device-removed", "app: Pixel 9")).let {
            assertEquals(LogKind.DEVICE_REMOVED, it.kind)
            assertFalse(it.passkey)
            assertEquals("Pixel 9", it.subject)
        }
        assertEquals(LogEntry(LogKind.ACCOUNT_LINKED, subject = "Google", at = 1_000), logEntry(event("linked", "google")))
        assertEquals("Codeberg", logEntry(event("unlinked", "codeberg")).subject)
        assertEquals(LogEntry(LogKind.SIGNED_IN, subject = "admin", at = 1_000), logEntry(event("signed-in", "admin")))
        assertEquals(LogEntry(LogKind.OTHER, subject = "teleported", at = 1_000), logEntry(event("teleported")))
        assertNull(logEntry(event("set", address = " ")).address)
    }

    @Test
    fun `unlinking confirms with the user key, then lists again`() = runTest {
        val userId = test.addIdentity()
        every { client.disconnectLinkedIdentity(github.sessionId, null) } returns ok()
        val vm = viewModel()
        val account = vm.ready().linked!!.single()

        every { client.listLinkedIdentities() } returns done(emptyList())
        vm.startUnlink(account)
        vm.unlink()
        val state = vm.state.first { it.unlink == null && !it.refreshing }
        assertEquals(emptyList<LinkedView>(), (state.overview as OverviewState.Ready).overview.linked)
        coVerify { nodes.asUser<Any?>(userId, node, any(), false, any()) }
        verify { client.disconnectLinkedIdentity(github.sessionId, null) }
    }

    @Test
    fun `unlinking asks for the passphrase the node wants, then sends it`() = runTest {
        test.addIdentity()
        val passphrases = mutableListOf<String?>()
        every { client.disconnectLinkedIdentity(github.sessionId, any()) } answers {
            passphrases += secondArg<String?>()
            if (secondArg<String?>() == null) refused<Void>(428) else ok()
        }
        val vm = viewModel()
        vm.startUnlink(vm.ready().linked!!.single())
        vm.unlink()
        assertTrue(vm.state.first { it.unlink?.needsPassphrase == true && it.unlink?.busy == false }.unlink!!.needsPassphrase)

        vm.onUnlinkPassphraseChange("secret")
        vm.unlink()
        vm.state.first { it.unlink == null }
        assertEquals(listOf(null, "secret"), passphrases)
    }

    @Test
    fun `an account with a passphrase is asked for it before unlinking`() = runTest {
        test.addIdentity()
        every { client.profile } returns done(profile(passphrase = true))
        every { client.disconnectLinkedIdentity(github.sessionId, "guess") } returns refused(403)
        val vm = viewModel()
        vm.startUnlink(vm.ready().linked!!.single())
        assertTrue(vm.state.value.unlink!!.needsPassphrase)
        vm.unlink()
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }

        vm.onUnlinkPassphraseChange("guess")
        vm.unlink()
        val unlink = vm.state.first { it.unlink?.error != null }.unlink!!
        assertEquals("wrong passphrase", unlink.error)
        assertEquals("", unlink.passphrase)
    }

    @Test
    fun `renewing registers the new key, removes the old, then stores the new one`() = runTest {
        val userId = test.addIdentity(deviceId = oldDeviceId.toString())
        val newKey = slot<Signature.KeyPair>()
        var registeredId: Id? = null
        every { client.registerDevice(capture(newKey), ThisDevice.name, ThisDevice.APP_NAME, null) } answers {
            // Captured now: the view model destroys its copy of the key once it is stored.
            registeredId = Id.of(newKey.captured.publicKey().bytes())
            ok()
        }
        every { client.removeDevice(oldDeviceId, null) } returns ok()
        val vm = viewModel()
        vm.ready()

        vm.startRenew()
        vm.renew()
        val renewal = vm.state.first { it.renewal?.step == RenewStep.DONE }.renewal!!
        assertFalse(renewal.oldKeyKept)

        val newId = registeredId!!.toString()
        assertEquals(newId, vault.identities.value.single().deviceId)
        assertArrayEquals(registeredId!!.bytes, vault.deviceKey(userId, AuthPrompt("x")).publicKey().bytes())
        coVerifyOrder {
            client.registerDevice(any(), any(), any(), null)
            client.removeDevice(oldDeviceId, null)
            nodes.forget(userId)
        }
        // One act of authority for both.
        assertEquals(1, prompts.size)
    }

    @Test
    fun `renewing asks for the passphrase the node wants, and nothing changes until then`() = runTest {
        val userId = test.addIdentity(deviceId = oldDeviceId.toString())
        every { client.registerDevice(any(), any(), any(), null) } returns refused(428)
        every { client.registerDevice(any(), any(), any(), "secret") } returns ok()
        every { client.removeDevice(oldDeviceId, "secret") } returns ok()
        val vm = viewModel()
        vm.ready()

        vm.startRenew()
        vm.renew()
        vm.state.first { it.renewal?.step == RenewStep.PASSPHRASE }
        assertEquals(oldDeviceId.toString(), vault.identities.value.single().deviceId)
        verify(exactly = 0) { client.removeDevice(any(), any()) }

        vm.onRenewPassphraseChange("secret")
        vm.renew()
        vm.state.first { it.renewal?.step == RenewStep.DONE }
        assertTrue(vault.identities.value.single().deviceId != oldDeviceId.toString())
        coVerify { nodes.forget(userId) }
    }

    @Test
    fun `an old key the node keeps is reported, the new one is used anyway`() = runTest {
        test.addIdentity(deviceId = oldDeviceId.toString())
        every { client.registerDevice(any(), any(), any(), null) } returns ok()
        every { client.removeDevice(oldDeviceId, null) } returns CompletableFuture.failedFuture(java.io.IOException("lost"))
        val vm = viewModel()
        vm.ready()
        vm.startRenew()
        vm.renew()
        assertTrue(vm.state.first { it.renewal?.step == RenewStep.DONE }.renewal!!.oldKeyKept)
        assertTrue(vault.identities.value.single().deviceId != oldDeviceId.toString())
    }

    @Test
    fun `a new key the phone couldn't store is stored on retry, without asking the node again`() = runTest {
        test.addIdentity(deviceId = oldDeviceId.toString())
        every { client.registerDevice(any(), any(), any(), null) } returns ok()
        every { client.removeDevice(oldDeviceId, null) } returns ok()
        val vm = viewModel()
        vm.ready()
        test.keystore.failSeals = 1

        vm.startRenew()
        vm.renew()
        assertEquals("cancelled", vm.state.first { it.renewal?.step == RenewStep.SAVE_FAILED }.renewal!!.error)
        assertEquals(oldDeviceId.toString(), vault.identities.value.single().deviceId)

        vm.renew()
        vm.state.first { it.renewal?.step == RenewStep.DONE }
        assertTrue(vault.identities.value.single().deviceId != oldDeviceId.toString())
        verify(exactly = 1) { client.registerDevice(any(), any(), any(), any()) }
    }

    @Test
    fun `without a checked backup, unlinking and renewing are refused and the backup offered`() = runTest {
        test.addIdentity(backupVerified = false, deviceId = oldDeviceId.toString())
        val vm = viewModel()
        val account = vm.ready().linked!!.single()
        vm.startUnlink(account)
        assertTrue(vm.state.value.backupNeeded)
        assertNull(vm.state.value.unlink)

        vm.dismissBackupNeeded()
        vm.startRenew()
        assertTrue(vm.state.value.backupNeeded)
        assertNull(vm.state.value.renewal)
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
    }
}
