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

package io.bosonnetwork.identity.core.boson

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.vault.IdentityVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Which of the identities on this phone the app is showing: the one the user picked in the switcher.
 * Only the choice is stored, in [store]; the identities themselves stay in the vault.
 */
class ActiveIdentity(
    private val vault: IdentityVault,
    private val store: DataStore<Preferences>,
) {
    /**
     * The identity in use: the one selected, or the first one when none is selected or the selected
     * one was removed; null when the phone holds no identity.
     */
    val active: Flow<IdentityInfo?> = flow {
        vault.load()
        emitAll(
            combine(vault.identities, store.data.map { it[SELECTED] }) { identities, selected ->
                identities.firstOrNull { it.userId == selected } ?: identities.firstOrNull()
            },
        )
    }.distinctUntilChanged()

    /** Makes [userId] the identity in use. */
    suspend fun select(userId: String) {
        store.edit { it[SELECTED] = userId }
    }

    private companion object {
        val SELECTED = stringPreferencesKey("selected_user_id")
    }
}
