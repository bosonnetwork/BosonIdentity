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
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.exceptions.RateLimitException
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.designsystem.component.PassphraseProblem
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Setting, changing and removing the passphrase, and recovery codes, over a real vault and a mocked node. */
@OptIn(ExperimentalCoroutinesApi::class)
class PassphraseViewModelTest {

    private val test = TestVault()
    private val node = TestVault.NODE
    private val client = mockk<DirectorClient>()
    private val nodes = mockk<NodeConnections>()
    private val prompts = mutableListOf<AuthPrompt>()
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.sec_wrong_passphrase) } returns "wrong passphrase"
        every { getString(R.string.sec_passphrase_set_done) } returns "set"
        every { getString(R.string.sec_passphrase_changed_done) } returns "changed"
        every { getString(R.string.sec_passphrase_removed_done) } returns "removed"
        every { getString(R.string.sec_passphrase_already_set) } returns "already has one"
        every { getString(R.string.sec_codes_after_set_failed, any()) } answers { "set, no codes: " + secondArg<Array<Any?>>()[0] }
        every { getString(R.string.sec_set_prompt_title) } returns "Set a passphrase"
        every { getString(R.string.sec_set_prompt_subtitle, any()) } answers { "For your account at " + secondArg<Array<Any?>>()[0] }
        every { getString(io.bosonnetwork.identity.core.designsystem.R.string.ds_error_network) } returns "no network"
        every { getString(io.bosonnetwork.identity.core.designsystem.R.string.ds_error_locked, any()) } returns "locked out"
    }
    private val viewModels = mutableListOf<PassphraseViewModel>()
    private val codes = listOf("AAAA-BBBB-CCCC-DDDD", "EEEE-FFFF-GGGG-HHHH")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { nodes.asUser<Any?>(any(), any(), capture(prompts), any(), any()) } coAnswers {
            arg<suspend (DirectorClient, Signature.KeyPair?) -> Any?>(4).invoke(client, null)
        }
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        test.close()
    }

    private suspend fun viewModel(backupVerified: Boolean = true): Pair<PassphraseViewModel, String> {
        val userId = test.addIdentity(backupVerified = backupVerified)
        val vm = PassphraseViewModel(test.active, nodes, context).also { viewModels += it }
        vm.state.first { it.identity != null }
        return vm to userId
    }

    @Test
    fun `a new passphrase must have 8 characters, 5 different, and be confirmed`() = runTest {
        val (vm) = viewModel()
        vm.start(PassphraseAction.SET)
        vm.onNewChange("short")
        assertEquals(PassphraseProblem.TOO_SHORT, vm.state.value.form!!.newProblem)
        vm.onNewChange("aaaabbbb")
        assertEquals(PassphraseProblem.TOO_PLAIN, vm.state.value.form!!.newProblem)
        vm.onConfirmChange("aaaabbbb")
        assertFalse(vm.state.value.form!!.canSubmit)
        vm.submit()

        vm.onNewChange("good passphrase")
        assertNull(vm.state.value.form!!.newProblem)
        assertTrue(vm.state.value.form!!.mismatch)
        assertFalse(vm.state.value.form!!.canSubmit)
        vm.onConfirmChange("good passphrase")
        assertFalse(vm.state.value.form!!.mismatch)
        assertTrue(vm.state.value.form!!.canSubmit)
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a first passphrase is followed by its recovery codes, in one act of authority`() = runTest {
        val (vm, userId) = viewModel()
        every { client.setPassphrase("good passphrase") } returns ok()
        every { client.makeRecoveryCodes("good passphrase") } returns done(codes)
        vm.start(PassphraseAction.SET)
        vm.onNewChange("good passphrase")
        vm.onConfirmChange("good passphrase")
        vm.submit()

        val state = vm.state.first { it.form == null }
        assertEquals(codes, state.codes)
        assertEquals("set", state.notice)
        assertEquals(1, state.changes)
        verifyOrder {
            client.setPassphrase("good passphrase")
            client.makeRecoveryCodes("good passphrase")
        }
        coVerify(exactly = 1) { nodes.asUser<Any?>(userId, node, any(), false, any()) }
        assertEquals(AuthPrompt("Set a passphrase", "For your account at node.example"), prompts.single())

        // Shown once: gone when saved.
        vm.codesSaved()
        assertNull(vm.state.value.codes)
    }

    @Test
    fun `a passphrase set without its codes says so`() = runTest {
        val (vm) = viewModel()
        every { client.setPassphrase("good passphrase") } returns ok()
        every { client.makeRecoveryCodes("good passphrase") } returns CompletableFuture.failedFuture(java.io.IOException("lost"))
        vm.start(PassphraseAction.SET)
        vm.onNewChange("good passphrase")
        vm.onConfirmChange("good passphrase")
        vm.submit()
        val state = vm.state.first { it.form == null }
        assertNull(state.codes)
        assertEquals("set, no codes: no network", state.notice)
        assertEquals(1, state.changes)
    }

    @Test
    fun `setting a passphrase an account already has turns into changing it`() = runTest {
        val (vm) = viewModel()
        every { client.setPassphrase(any()) } returns refused(428)
        vm.start(PassphraseAction.SET)
        vm.onNewChange("good passphrase")
        vm.onConfirmChange("good passphrase")
        vm.submit()
        val form = vm.state.first { it.form?.busy == false && it.form?.error != null }.form!!
        assertEquals(PassphraseAction.CHANGE, form.action)
        assertEquals("already has one", form.error)
        assertFalse(form.canSubmit)
    }

    @Test
    fun `changing takes the current passphrase and the new one`() = runTest {
        val (vm) = viewModel()
        every { client.updatePassphrase("old passphrase", "new passphrase") } returns ok()
        vm.start(PassphraseAction.CHANGE)
        vm.onCurrentChange("old passphrase")
        vm.onNewChange("new passphrase")
        vm.onConfirmChange("new passphrase")
        vm.submit()
        val state = vm.state.first { it.form == null }
        assertEquals("changed", state.notice)
        assertNull(state.codes)
        verify { client.updatePassphrase("old passphrase", "new passphrase") }
    }

    @Test
    fun `a wrong current passphrase is reported as one, and cleared`() = runTest {
        val (vm) = viewModel()
        every { client.updatePassphrase("guess", "new passphrase") } returns refused(403)
        vm.start(PassphraseAction.CHANGE)
        vm.onCurrentChange("guess")
        vm.onNewChange("new passphrase")
        vm.onConfirmChange("new passphrase")
        vm.submit()
        val form = vm.state.first { it.form?.error != null }.form!!
        assertEquals("wrong passphrase", form.error)
        assertEquals("", form.current)
        assertEquals("new passphrase", form.new)
        assertEquals(0, vm.state.value.changes)
    }

    @Test
    fun `too many wrong passphrases lock the account for a while, and say so`() = runTest {
        val (vm) = viewModel()
        every { client.clearPassphrase("guess") } returns CompletableFuture.failedFuture(RateLimitException("slow down", 600))
        vm.start(PassphraseAction.REMOVE)
        vm.onCurrentChange("guess")
        vm.submit()
        assertEquals("locked out", vm.state.first { it.form?.error != null }.form!!.error)
    }

    @Test
    fun `removing takes the current passphrase`() = runTest {
        val (vm) = viewModel()
        every { client.clearPassphrase("old passphrase") } returns ok()
        vm.start(PassphraseAction.REMOVE)
        assertFalse(vm.state.value.form!!.canSubmit)
        vm.onCurrentChange("old passphrase")
        vm.submit()
        assertEquals("removed", vm.state.first { it.form == null }.notice)
        verify { client.clearPassphrase("old passphrase") }
    }

    @Test
    fun `new recovery codes take the passphrase, and are shown`() = runTest {
        val (vm) = viewModel()
        every { client.makeRecoveryCodes("old passphrase") } returns done(codes)
        vm.start(PassphraseAction.NEW_CODES)
        vm.onCurrentChange("old passphrase")
        vm.submit()
        val state = vm.state.first { it.form == null }
        assertEquals(codes, state.codes)
        assertNull(state.notice)
        verify(exactly = 0) { client.setPassphrase(any()) }
    }

    @Test
    fun `without a checked backup, nothing is changed and the backup offered`() = runTest {
        val (vm) = viewModel(backupVerified = false)
        vm.start(PassphraseAction.SET)
        assertTrue(vm.state.value.backupNeeded)
        assertNull(vm.state.value.form)
        vm.dismissBackupNeeded()
        assertFalse(vm.state.value.backupNeeded)
    }
}
