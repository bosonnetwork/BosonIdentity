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
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.DirectorGuest
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.OAuthReturns
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

/**
 * Resetting a forgotten passphrase with a recovery code, a linked account or a passkey, over a real
 * vault, a mocked node, and the real hand-over of returns from account providers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForgotPassphraseViewModelTest {

    private val test = TestVault()
    private val node = TestVault.NODE
    private val client = mockk<DirectorClient>()
    private val guest = mockk<DirectorGuest>()
    private val nodes = mockk<NodeConnections>()
    private val returns = OAuthReturns()
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.sec_forgot_code_invalid) } returns "wrong code"
        every { getString(R.string.sec_forgot_grant_expired) } returns "too late"
        every { getString(R.string.sec_forgot_account_refused) } returns "not linked"
        every { getString(R.string.sec_provider_error, any()) } answers { "refused: " + secondArg<Array<Any?>>()[0] }
    }
    private val viewModels = mutableListOf<ForgotPassphraseViewModel>()
    private val resetUrl = "https://node.example/api/v1/auth/oauth/github/authorize?purpose=passphrase-reset"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { nodes.deviceClient(any(), any()) } returns client
        coEvery { nodes.guest(node) } returns guest
        every { client.recoveryMethods } returns done(recoveryMethods(oauth = 1, passkeys = 1, codes = 4))
        every { guest.providers } returns done(listOf(provider("github", "GitHub")))
        every { guest.passphraseResetUrl("github", OAuthReturns.REDIRECT_URI) } returns resetUrl
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        test.close()
    }

    private suspend fun opened(): Pair<ForgotPassphraseViewModel, String> {
        val userId = test.addIdentity()
        val vm = ForgotPassphraseViewModel(test.active, nodes, returns, context).also { viewModels += it }
        vm.state.first { it.identity != null }
        vm.open()
        vm.state.first { it.step == ForgotStep.CHOOSE }
        return vm to userId
    }

    private fun ForgotPassphraseViewModel.typeNew(passphrase: String) {
        onNewChange(passphrase)
        onConfirmChange(passphrase)
    }

    @Test
    fun `the ways offered are the ones the account has`() = runTest {
        val (vm) = opened()
        val state = vm.state.value
        assertEquals(RecoveryView(accounts = 1, passkeys = 1, recoveryCodes = 4), state.methods)
        assertEquals(listOf(ProviderView("github", "GitHub")), state.providers)

        // Without a linked account, the providers aren't even asked for.
        every { client.recoveryMethods } returns done(recoveryMethods(codes = 1))
        vm.open()
        val codesOnly = vm.state.first { it.step == ForgotStep.CHOOSE }
        assertEquals(emptyList<ProviderView>(), codesOnly.providers)
        verify(exactly = 1) { guest.providers }

        every { client.recoveryMethods } returns done(recoveryMethods())
        vm.open()
        assertFalse(vm.state.first { it.step == ForgotStep.CHOOSE }.methods!!.any)
    }

    @Test
    fun `a recovery code gives a grant, which sets the new passphrase`() = runTest {
        val (vm, userId) = opened()
        every { guest.redeemRecoveryCode(Id.of(userId), "AAAA-BBBB-CCCC-DDDD") } returns done("grant-1")
        every { guest.resetPassphrase("grant-1", "new passphrase") } returns ok()
        vm.useCode()
        vm.onCodeChange(" AAAA-BBBB-CCCC-DDDD ")
        vm.submitCode()
        val holding = vm.state.first { it.step == ForgotStep.NEW_PASSPHRASE }
        assertTrue(holding.holdsGrant)

        vm.typeNew("short")
        assertFalse(vm.state.value.canReset)
        vm.reset()
        verify(exactly = 0) { guest.resetPassphrase(any(), any()) }

        vm.typeNew("new passphrase")
        vm.reset()
        vm.state.first { it.step == ForgotStep.DONE }
        verify { guest.resetPassphrase("grant-1", "new passphrase") }
        // None of this takes the identity's key.
        coVerify(exactly = 0) { nodes.asUser<Any?>(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a spent code's grant is kept through a refusal, until Cancel`() = runTest {
        val (vm, userId) = opened()
        every { guest.redeemRecoveryCode(Id.of(userId), any()) } returns done("grant-1")
        every { guest.resetPassphrase("grant-1", "first try!") } returns refused(400)
        every { guest.resetPassphrase("grant-1", "second try!") } returns ok()
        vm.useCode()
        vm.onCodeChange("AAAA-BBBB-CCCC-DDDD")
        vm.submitCode()
        vm.state.first { it.step == ForgotStep.NEW_PASSPHRASE }

        vm.typeNew("first try!")
        vm.reset()
        val refused = vm.state.first { it.error != null }
        assertEquals(ForgotStep.NEW_PASSPHRASE, refused.step)
        assertTrue(refused.holdsGrant)

        // Back from a code or a browser, but not from a held grant.
        vm.backToChoose()
        assertEquals(ForgotStep.NEW_PASSPHRASE, vm.state.value.step)

        vm.typeNew("second try!")
        vm.reset()
        vm.state.first { it.step == ForgotStep.DONE }
        verify { guest.resetPassphrase("grant-1", "second try!") }
    }

    @Test
    fun `Cancel drops the grant`() = runTest {
        val (vm, userId) = opened()
        every { guest.redeemRecoveryCode(Id.of(userId), any()) } returns done("grant-1")
        vm.useCode()
        vm.onCodeChange("AAAA-BBBB-CCCC-DDDD")
        vm.submitCode()
        vm.state.first { it.step == ForgotStep.NEW_PASSPHRASE }

        vm.close()
        assertEquals(ForgotStep.CLOSED, vm.state.value.step)
        assertFalse(vm.state.value.holdsGrant)
        vm.typeNew("new passphrase")
        vm.reset()
        verify(exactly = 0) { guest.resetPassphrase(any(), any()) }
    }

    @Test
    fun `a wrong code says so, and the code can be typed again`() = runTest {
        val (vm) = opened()
        every { guest.redeemRecoveryCode(any(), any()) } returns refused(403)
        vm.useCode()
        vm.onCodeChange("WRONG")
        vm.submitCode()
        val state = vm.state.first { it.error != null }
        assertEquals("wrong code", state.error)
        assertEquals(ForgotStep.CODE, state.step)
    }

    @Test
    fun `an expired grant starts over`() = runTest {
        val (vm) = opened()
        every { guest.redeemRecoveryCode(any(), any()) } returns done("grant-1")
        every { guest.resetPassphrase("grant-1", any()) } returns refused(401)
        vm.useCode()
        vm.onCodeChange("AAAA-BBBB-CCCC-DDDD")
        vm.submitCode()
        vm.state.first { it.step == ForgotStep.NEW_PASSPHRASE }
        vm.typeNew("new passphrase")
        vm.reset()
        val state = vm.state.first { it.error != null }
        assertEquals(ForgotStep.CHOOSE, state.step)
        assertEquals("too late", state.error)
    }

    @Test
    fun `a linked account resets through its provider, returning a grant to the app`() = runTest {
        val (vm) = opened()
        every { guest.resetPassphrase("grant-from-github", "new passphrase") } returns ok()
        vm.useAccount(ProviderView("github", "GitHub"))
        val waiting = vm.state.value
        assertEquals(ForgotStep.WAITING, waiting.step)
        assertEquals(resetUrl, waiting.openUrl)
        vm.urlOpened()
        assertNull(vm.state.value.openUrl)

        // A sign-in token is not a reset grant: it is ignored.
        returns.deliver("token=not-for-this")
        assertEquals(ForgotStep.WAITING, vm.state.value.step)

        returns.deliver("grant=grant-from-github")
        assertTrue(vm.state.first { it.step == ForgotStep.NEW_PASSPHRASE }.holdsGrant)
        vm.typeNew("new passphrase")
        vm.reset()
        vm.state.first { it.step == ForgotStep.DONE }
        verify { guest.resetPassphrase("grant-from-github", "new passphrase") }
    }

    @Test
    fun `a provider that refuses the reset says so plainly`() = runTest {
        val (vm) = opened()
        vm.useAccount(ProviderView("github", "GitHub"))
        returns.deliver("error=reset_failed")
        val state = vm.state.first { it.step == ForgotStep.CHOOSE }
        assertEquals("not linked", state.error)

        vm.useAccount(ProviderView("github", "GitHub"))
        returns.deliver("error=access_denied")
        assertEquals("refused: access_denied", vm.state.first { it.step == ForgotStep.CHOOSE }.error)
    }

    @Test
    fun `a passkey resets on the web, at the node's portal`() = runTest {
        val (vm) = opened()
        vm.usePasskey()
        assertEquals("https://node.example/portal/", vm.state.value.openUrl)
    }
}
