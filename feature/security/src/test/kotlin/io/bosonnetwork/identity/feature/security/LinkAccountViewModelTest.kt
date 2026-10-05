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
import io.bosonnetwork.director.client.DirectorGuest
import io.bosonnetwork.director.client.DirectorOAuth
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.OAuthReturns
import io.mockk.coEvery
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Linking an account at a provider: the browser round trip through the real hand-over of returns,
 * then binding with the user key from a real vault (with a stand-in for the Keystore), over a mocked node.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkAccountViewModelTest {

    private val test = TestVault()
    private val node = TestVault.NODE
    private val guest = mockk<DirectorGuest>()
    private val oauth = mockk<DirectorOAuth>()
    private val nodes = mockk<NodeConnections>()
    private val returns = OAuthReturns()
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.sec_wrong_passphrase) } returns "wrong passphrase"
        every { getString(R.string.sec_link_conflict) } returns "linked elsewhere"
        every { getString(R.string.sec_link_session_gone) } returns "sign in again"
        every { getString(R.string.sec_link_no_providers) } returns "no providers"
        every { getString(R.string.sec_provider_error, any()) } answers { "refused: " + secondArg<Array<Any?>>()[0] }
    }
    private val viewModels = mutableListOf<LinkAccountViewModel>()
    private val github = ProviderView("github", "GitHub")
    private val authorizeUrl = "https://node.example/api/v1/auth/oauth/github/authorize"
    private val userKey = Signature.KeyPair.random()
    private val boundKeys = mutableListOf<Id>()
    private val passphrases = mutableListOf<String?>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { nodes.guest(node) } returns guest
        every { nodes.oauth(node, any()) } returns oauth
        every { guest.providers } returns done(listOf(provider("github", "GitHub"), provider("google", "Google")))
        every { guest.authorizeUrl("github", OAuthReturns.REDIRECT_URI) } returns authorizeUrl
        every { oauth.close() } returns ok()
        bindAnswers(done(Id.of(userKey.publicKey().bytes())))
    }

    // Records who binds and with which passphrase, and answers [answer].
    private fun bindAnswers(vararg answer: java.util.concurrent.CompletableFuture<Id>) {
        val queue = ArrayDeque(answer.toList())
        every { oauth.bindUserIdentity(any(), any()) } answers {
            boundKeys += Id.of(firstArg<Signature.KeyPair>().publicKey().bytes())
            passphrases += secondArg<String?>()
            if (queue.size > 1) queue.removeFirst() else queue.first()
        }
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        test.close()
    }

    private suspend fun viewModel(backupVerified: Boolean = true): Pair<LinkAccountViewModel, String> {
        val userId = test.addIdentity(backupVerified = backupVerified, userKey = userKey)
        val vm = LinkAccountViewModel(test.active, test.vault, nodes, returns, context).also { viewModels += it }
        vm.state.first { it.identity != null }
        return vm to userId
    }

    private suspend fun LinkAccountViewModel.signedIn(passphraseProtected: Boolean = false, token: String = "tok") {
        start(passphraseProtected)
        state.first { it.step == LinkStep.CHOOSE }
        choose(github)
        assertEquals(authorizeUrl, state.value.openUrl)
        urlOpened()
        returns.deliver("token=$token")
    }

    @Test
    fun `the providers are the node's`() = runTest {
        val (vm) = viewModel()
        vm.start(false)
        assertEquals(listOf(github, ProviderView("google", "Google")), vm.state.first { it.step == LinkStep.CHOOSE }.providers)

        every { guest.providers } returns done(emptyList())
        vm.start(false)
        assertEquals("no providers", vm.state.first { it.step == LinkStep.CHOOSE }.error)
    }

    @Test
    fun `the sign-in's token is bound to the identity with its key`() = runTest {
        val (vm, userId) = viewModel()
        vm.signedIn()
        val done = vm.state.first { it.step == LinkStep.DONE }
        assertEquals(1, done.changes)
        assertEquals(listOf(Id.of(userId)), boundKeys)
        assertEquals(listOf<String?>(null), passphrases)
        verify { nodes.oauth(node, "tok") }
        verify { oauth.close() }
    }

    @Test
    fun `a passphrase the node asks for is asked, and the same sign-in tried again`() = runTest {
        val (vm) = viewModel()
        bindAnswers(refused(428), done(Id.random()))
        vm.signedIn()
        val confirm = vm.state.first { it.step == LinkStep.CONFIRM }
        assertTrue(confirm.needsPassphrase)
        assertNull(confirm.error)

        vm.link()
        assertEquals(1, passphrases.size)

        vm.onPassphraseChange("secret")
        vm.link()
        vm.state.first { it.step == LinkStep.DONE }
        assertEquals(listOf(null, "secret"), passphrases)
        // No new round trip through the browser: the same token, twice.
        verify(exactly = 2) { nodes.oauth(node, "tok") }
        verify(exactly = 1) { guest.authorizeUrl(any(), any()) }
        verify(exactly = 2) { oauth.close() }
    }

    @Test
    fun `an account with a passphrase is asked for it before the key is used`() = runTest {
        val (vm) = viewModel()
        vm.signedIn(passphraseProtected = true)
        assertEquals(LinkStep.CONFIRM, vm.state.first { it.step == LinkStep.CONFIRM }.step)
        verify(exactly = 0) { oauth.bindUserIdentity(any(), any()) }

        vm.onPassphraseChange("secret")
        vm.link()
        vm.state.first { it.step == LinkStep.DONE }
        assertEquals(listOf<String?>("secret"), passphrases)
    }

    @Test
    fun `a wrong passphrase is reported, and the sign-in kept`() = runTest {
        val (vm) = viewModel()
        bindAnswers(refused(403), done(Id.random()))
        vm.signedIn(passphraseProtected = true)
        vm.state.first { it.step == LinkStep.CONFIRM }
        vm.onPassphraseChange("guess")
        vm.link()
        val state = vm.state.first { it.error != null }
        assertEquals(LinkStep.CONFIRM, state.step)
        assertEquals("wrong passphrase", state.error)

        vm.onPassphraseChange("secret")
        vm.link()
        vm.state.first { it.step == LinkStep.DONE }
    }

    @Test
    fun `an account linked to another identity is refused`() = runTest {
        val (vm) = viewModel()
        bindAnswers(refused(409))
        vm.signedIn()
        val state = vm.state.first { it.step == LinkStep.CHOOSE && it.error != null }
        assertEquals("linked elsewhere", state.error)
        // The token is gone: linking again needs a new sign-in.
        vm.link()
        verify(exactly = 1) { oauth.bindUserIdentity(any(), any()) }
    }

    @Test
    fun `a sign-in the node no longer knows takes a new one`() = runTest {
        val (vm) = viewModel()
        bindAnswers(refused(428), refused(401))
        vm.signedIn()
        vm.state.first { it.step == LinkStep.CONFIRM }
        vm.onPassphraseChange("secret")
        vm.link()
        val state = vm.state.first { it.step == LinkStep.CHOOSE && it.error != null }
        assertEquals("sign in again", state.error)

        bindAnswers(done(Id.random()))
        vm.choose(github)
        returns.deliver("token=fresh")
        vm.state.first { it.step == LinkStep.CONFIRM }
        vm.onPassphraseChange("secret")
        vm.link()
        vm.state.first { it.step == LinkStep.DONE }
        verify { nodes.oauth(node, "fresh") }
        verify(exactly = 2) { guest.authorizeUrl("github", OAuthReturns.REDIRECT_URI) }
    }

    @Test
    fun `a refused sign-in at the provider says so`() = runTest {
        val (vm) = viewModel()
        vm.start(false)
        vm.state.first { it.step == LinkStep.CHOOSE }
        vm.choose(github)
        // A reset grant is not a sign-in: it is ignored.
        returns.deliver("grant=not-for-this")
        assertEquals(LinkStep.WAITING, vm.state.value.step)
        returns.deliver("error=access_denied")
        assertEquals("refused: access_denied", vm.state.first { it.step == LinkStep.CHOOSE }.error)
        verify(exactly = 0) { oauth.bindUserIdentity(any(), any()) }
    }

    @Test
    fun `without a checked backup, linking is refused and the backup offered`() = runTest {
        val (vm) = viewModel(backupVerified = false)
        vm.start(false)
        assertTrue(vm.state.value.backupNeeded)
        assertEquals(LinkStep.CLOSED, vm.state.value.step)
        verify(exactly = 0) { guest.providers }
    }
}
