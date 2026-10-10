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

package io.bosonnetwork.identity.feature.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.LocalProfile
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.model.ProfileFields
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.core.vault.KeyProtector
import io.bosonnetwork.identity.core.vault.MoveCode
import io.bosonnetwork.identity.core.vault.Protection
import io.bosonnetwork.identity.core.vault.Sealed
import io.bosonnetwork.identity.core.vault.VaultStore
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Moving the identity to a new phone, on the old phone, over a real vault with a stand-in for the Keystore. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveOutViewModelTest {

    private class Keystore : KeyProtector {
        var dismiss = false
        override fun isAvailable() = true
        override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt) =
            Sealed(ByteArray(12), plaintext.copyOf())
        override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt): ByteArray {
            if (dismiss) throw AppError.AuthenticationCancelled("dismissed")
            return sealed.data.copyOf()
        }
        override fun delete(alias: String) = Unit
    }

    private val dir: File = Files.createTempDirectory("moveout").toFile()
    private val keystore = Keystore()
    private val vault = IdentityVault(VaultStore(File(dir, "vault")), keystore)
    private val context = mockk<Context>(relaxed = true)
    private val userKey = Signature.KeyPair.random()
    private val node = NodeAddress("https://node.example", Id.random().toString())
    private val viewModels = mutableListOf<MoveOutViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private suspend fun viewModel(homeNode: NodeAddress? = node): MoveOutViewModel {
        val userId = vault.add(userKey, null, true, keepUserKey = true, prompt = AuthPrompt("Confirm")).userId
        vault.update(userId) {
            it.copy(homeNode = homeNode, profile = LocalProfile(ProfileFields(name = "Alice", bio = "Hi"), 3, ProfileFields(name = "Alice", bio = "Hi")))
        }
        return MoveOutViewModel(SavedStateHandle(mapOf(MoveOutViewModel.ARG_USER_ID to userId)), vault, context).also { viewModels += it }
    }

    @Test
    fun `the code shows first, then the QR code that it opens`() = runTest {
        val vm = viewModel()
        vm.start()
        val coded = vm.state.first { it.step == MoveOutStep.CODE }
        val code = coded.code!!
        assertTrue(Regex("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{2}").matches(code))

        val payload = MoveCode.open(coded.qr!!, code)
        assertArrayEquals(userKey.privateKey().bytes(), payload.privateKey)
        assertEquals(node, payload.homeNode)
        assertEquals("Alice", payload.profile.fields.name)

        vm.showQr()
        assertEquals(MoveOutStep.QR, vm.state.value.step)
        assertEquals(MoveOutViewModel.QR_SECONDS, vm.state.value.remainingSeconds)
        vm.backToCode()
        assertEquals(MoveOutStep.CODE, vm.state.value.step)
    }

    @Test
    fun `putting the QR code away forgets it and the code`() = runTest {
        val vm = viewModel()
        vm.start()
        vm.state.first { it.step == MoveOutStep.CODE }
        vm.showQr()
        vm.hide()
        val done = vm.state.value
        assertEquals(MoveOutStep.DONE, done.step)
        assertNull(done.code)
        assertNull(done.qr)
    }

    @Test
    fun `an identity without a node can move, its avatar staying here`() = runTest {
        val vm = viewModel(homeNode = null)
        assertTrue(vm.state.first { it.noNode }.noNode)
        vm.start()
        val coded = vm.state.first { it.step == MoveOutStep.CODE }
        assertNull(MoveCode.open(coded.qr!!, coded.code!!).homeNode)
    }

    @Test
    fun `a dismissed screen lock shows nothing`() = runTest {
        keystore.dismiss = true
        val vm = viewModel()
        vm.start()
        val state = vm.state.first { it.step == MoveOutStep.INTRO }
        assertNull(state.code)
        assertNull(state.qr)
    }
}
