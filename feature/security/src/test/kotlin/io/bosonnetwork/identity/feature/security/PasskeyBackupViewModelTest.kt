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
import io.bosonnetwork.identity.core.backup.PasskeyBackup
import io.bosonnetwork.identity.core.backup.PrfUnsupportedException
import io.bosonnetwork.identity.core.model.AppError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Before
import org.junit.Test

/** The passkey backup section, with the backup itself mocked. */
@OptIn(ExperimentalCoroutinesApi::class)
class PasskeyBackupViewModelTest {
    private val test = TestVault()
    private val backup = mockk<PasskeyBackup>(relaxUnitFun = true)
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.sec_pkb_backup_first) } returns "back up first"
        every { getString(R.string.sec_pkb_no_prf) } returns "no prf"
    }
    private val viewModels = mutableListOf<PasskeyBackupViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        test.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = PasskeyBackupViewModel(test.active, backup, context).also { viewModels += it }

    @Test
    fun `it is turned on after the warning, and off again`() = runTest {
        val userId = test.addIdentity()
        coEvery { backup.isEnabled(userId) } returns false
        val vm = viewModel()
        vm.state.first { it.status == BackupStatus.OFF }
        vm.startEnable()
        assert(vm.state.value.confirming)
        vm.enable()
        vm.state.first { it.status == BackupStatus.ON }
        coVerify { backup.enable(userId, any(), any()) }
        vm.disable()
        vm.state.first { it.status == BackupStatus.OFF }
        coVerify { backup.disable(userId) }
    }

    @Test
    fun `there is no passkey backup before the key's own backup is checked`() = runTest {
        val userId = test.addIdentity(backupVerified = false)
        coEvery { backup.isEnabled(userId) } returns false
        val vm = viewModel()
        vm.state.first { it.status == BackupStatus.OFF }
        vm.startEnable()
        assertFalse(vm.state.value.confirming)
        assertEquals("back up first", vm.state.value.error)
    }

    @Test
    fun `a provider without PRF says so, and a dismissed sheet changes nothing`() = runTest {
        val userId = test.addIdentity()
        coEvery { backup.isEnabled(userId) } returns false
        coEvery { backup.enable(userId, any(), any()) } throws PrfUnsupportedException("no")
        val vm = viewModel()
        vm.state.first { it.status == BackupStatus.OFF }
        vm.enable()
        assertEquals("no prf", vm.state.first { it.error != null }.error)

        coEvery { backup.enable(userId, any(), any()) } throws AppError.AuthenticationCancelled("no")
        vm.enable()
        val state = vm.state.first { !it.busy }
        assertEquals(BackupStatus.OFF, state.status)
        assertEquals(null, state.error)
    }

    @Test
    fun `a phone without Block Store shows it as unavailable`() = runTest {
        val userId = test.addIdentity()
        coEvery { backup.isEnabled(userId) } throws IllegalStateException("no Play services")
        val vm = viewModel()
        vm.state.first { it.status == BackupStatus.UNAVAILABLE }
    }
}
