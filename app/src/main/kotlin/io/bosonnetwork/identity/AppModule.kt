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

package io.bosonnetwork.identity

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.OAuthReturns
import io.bosonnetwork.identity.core.vault.ActivityAuthenticator
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.core.vault.KeystoreProtector
import io.bosonnetwork.identity.core.vault.VaultStore
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun authenticator(): ActivityAuthenticator = ActivityAuthenticator()

    /**
     * The vault lives in the no-backup directory: on top of the backup rules that exclude everything,
     * the system never copies this directory anywhere.
     */
    @Provides
    @Singleton
    fun vault(@ApplicationContext context: Context, authenticator: ActivityAuthenticator): IdentityVault =
        IdentityVault(
            VaultStore(File(context.noBackupFilesDir, "vault")),
            KeystoreProtector(context, authenticator),
        )

    @Provides
    @Singleton
    fun nodes(vault: IdentityVault): NodeConnections = NodeConnections(vault)

    /** Which identity the app shows; only the choice is stored, never anything about the keys. */
    @Provides
    @Singleton
    fun activeIdentity(@ApplicationContext context: Context, vault: IdentityVault): ActiveIdentity =
        ActiveIdentity(vault, PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("active_identity") })

    /** Returns from account providers, handed from the activity to the flow waiting for them. */
    @Provides
    @Singleton
    fun oauthReturns(): OAuthReturns = OAuthReturns()
}
