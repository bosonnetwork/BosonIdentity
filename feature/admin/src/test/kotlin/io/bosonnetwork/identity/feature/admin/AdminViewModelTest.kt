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

package io.bosonnetwork.identity.feature.admin

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.AdminState
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.core.vault.KeyProtector
import io.bosonnetwork.identity.core.vault.Protection
import io.bosonnetwork.identity.core.vault.Sealed
import io.bosonnetwork.identity.core.vault.VaultStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Opening and ending admin mode, over a real vault (with a stand-in for the Keystore), a real
 * DataStore for the identity in use, and a stand-in for the session.
 */
class AdminViewModelTest {

    private class NoKeystore : KeyProtector {
        override fun isAvailable() = true
        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt) =
            Sealed(ByteArray(12), plaintext.copyOf())
        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt) = sealed.data.copyOf()
        override fun delete(alias: String) = Unit
    }

    private val fake = FakeAdmin()
    private val node = fake.node
    private val dir: File = Files.createTempDirectory("admin").toFile()
    private val vault = IdentityVault(VaultStore(File(dir, "vault")), NoKeystore())
    private val storeJob = Job()
    private val active = ActiveIdentity(
        vault,
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob)) { File(dir, "active.preferences_pb") },
    )

    @Before
    fun setUp() {
        fake.setUp()
        every { fake.context.getString(R.string.adm_not_admin, any()) } answers { "not admin of ${(args[1] as Array<*>)[0]}" }
    }

    @After
    fun tearDown() {
        fake.tearDown()
        storeJob.cancel()
        dir.deleteRecursively()
    }

    private suspend fun addIdentity(backupVerified: Boolean = true): String {
        val userId = vault.add(Signature.KeyPair.random(), null, backupVerified, keepUserKey = true, prompt = AuthPrompt("Confirm")).userId
        vault.update(userId) { it.copy(homeNode = node) }
        return userId
    }

    private suspend fun viewModel(): AdminViewModel =
        fake.track(AdminViewModel(active, vault, fake.session, fake.context)).also { vm -> vm.state.first { it.identity != null } }

    @Test
    fun `opening the tab asks nothing, unlocking opens admin mode and remembers it`() = runTest {
        val userId = addIdentity()
        coEvery { fake.session.start(any(), node, any()) } answers {
            AdminState(userId, node, 600_000).also { fake.state.value = it }
        }
        val vm = viewModel()
        assertNull(vm.state.value.session)
        coVerify(exactly = 0) { fake.session.start(any(), any(), any()) }

        vm.start()
        val session = vm.state.first { it.session != null && !it.starting }.session!!
        assertEquals(userId, session.userId)
        assertTrue(vault.identities.value.single().administers)

        vm.end()
        vm.state.first { it.session == null }
    }

    @Test
    fun `opening the page asks for admin mode once`() = runTest {
        val userId = addIdentity()
        coEvery { fake.session.start(any(), node, any()) } throws AppError.AuthenticationCancelled("no")
        val vm = viewModel()
        vm.enter()
        vm.state.first { !it.starting }
        // Coming back to the page (a rotation, say) doesn't ask again; its button does.
        vm.enter()
        coVerify(exactly = 1) { fake.session.start(match { it.userId == userId }, node, any()) }
    }

    @Test
    fun `a page opened before the identity is known still asks for admin mode`() = runTest {
        addIdentity()
        coEvery { fake.session.start(any(), node, any()) } throws AppError.AuthenticationCancelled("no")
        // Entered at once, as the page does on its first frame.
        val vm = fake.track(AdminViewModel(active, vault, fake.session, fake.context))
        vm.enter()
        vm.state.first { it.identity != null && !it.starting }
        coVerify(timeout = 5_000, exactly = 1) { fake.session.start(any(), node, any()) }
    }

    @Test
    fun `leaving the page ends admin mode`() = runTest {
        val userId = addIdentity()
        val store = ViewModelStore()
        val vm = fake.track(
            ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = AdminViewModel(active, vault, fake.session, fake.context) as T
                },
            )[AdminViewModel::class.java],
        )
        vm.state.first { it.identity != null }
        fake.state.value = AdminState(userId, node, Long.MAX_VALUE)
        vm.state.first { it.session != null }

        store.clear()
        verify { fake.session.endSoon() }
        assertNull(fake.state.value)
    }

    @Test
    fun `a refused identity no longer counts as an administrator`() = runTest {
        val userId = addIdentity()
        vault.update(userId) { it.copy(administers = true) }
        coEvery { fake.session.start(any(), any(), any()) } throws AppError.Forbidden("no")
        val vm = viewModel()
        vm.start()
        vm.state.first { it.error != null }
        assertFalse(vault.identities.first { ids -> !ids.single().administers }.single().administers)
    }

    @Test
    fun `an identity the node refuses is told it doesn't administer it`() = runTest {
        addIdentity()
        coEvery { fake.session.start(any(), any(), any()) } throws AppError.Unauthorized("no")
        val vm = viewModel()
        vm.start()
        assertEquals("not admin of node.example", vm.state.first { it.error != null }.error)
        assertFalse(vm.state.value.starting)
        assertFalse(vault.identities.value.single().administers)
    }

    @Test
    fun `a dismissed prompt leaves things as they were`() = runTest {
        addIdentity()
        coEvery { fake.session.start(any(), any(), any()) } throws AppError.AuthenticationCancelled("no")
        val vm = viewModel()
        vm.start()
        val state = vm.state.first { !it.starting }
        assertNull(state.error)
        assertNull(state.session)
    }

    @Test
    fun `without a checked backup, admin mode doesn't open`() = runTest {
        addIdentity(backupVerified = false)
        val vm = viewModel()
        vm.start()
        coVerify(exactly = 0) { fake.session.start(any(), any(), any()) }
        assertTrue(vm.state.value.error != null)
    }

    @Test
    fun `a session of another identity is ended, not shown`() = runTest {
        addIdentity()
        val vm = viewModel()
        fake.state.value = AdminState("someone-else", node, Long.MAX_VALUE)
        coVerify { fake.session.end() }
        assertNull(vm.state.value.session)
    }

    @Test
    fun `the time shown left is rounded up to the second`() {
        assertEquals("10:00", timeLeft(600_000))
        assertEquals("9:42", timeLeft(581_500))
        assertEquals("0:01", timeLeft(1))
        assertEquals("0:00", timeLeft(0))
        assertEquals("0:00", timeLeft(-5))
    }
}
