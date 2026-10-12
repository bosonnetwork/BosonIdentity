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
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.bosonnetwork.identity.core.backup.CredentialManagerPrf
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.AvatarStore
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.OAuthReturns
import io.bosonnetwork.identity.core.designsystem.theme.BosonIdentityTheme
import io.bosonnetwork.identity.core.model.ThemeMode
import io.bosonnetwork.identity.core.vault.ActivityAuthenticator
import io.bosonnetwork.identity.core.vault.IdentityVault
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The single activity. It is a FragmentActivity because the system authentication prompt
 * (BiometricPrompt) is shown over one.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var authenticator: ActivityAuthenticator
    @Inject lateinit var vault: IdentityVault
    @Inject lateinit var nodes: NodeConnections
    @Inject lateinit var activeIdentity: ActiveIdentity
    @Inject lateinit var avatarCache: AvatarStore
    @Inject lateinit var oauthReturns: OAuthReturns
    @Inject lateinit var passkeyPrf: CredentialManagerPrf

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppSettings.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authenticator.attach(this)
        passkeyPrf.attach(this)
        enableEdgeToEdge()
        setContent {
            var theme by remember { mutableStateOf(AppSettings.theme(this)) }
            ThemedContent(theme) {
                AppNavigation(
                    vault = vault,
                    activeIdentity = activeIdentity,
                    avatarCache = avatarCache,
                    theme = theme,
                    onThemeChange = {
                        AppSettings.setTheme(this, it)
                        theme = it
                    },
                    language = AppSettings.language(this),
                    onLanguageChange = { AppSettings.setLanguageAndRestart(this, it) },
                )
            }
        }
        // Started by a return from an account provider (the app was not running).
        if (savedInstanceState == null) deliverReturn(intent)
    }

    // The screen lock is asked over the activity in front: this one again when the user comes back from
    // another (an app's request) that took it.
    override fun onResume() {
        super.onResume()
        authenticator.attach(this)
        passkeyPrf.attach(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deliverReturn(intent)
    }

    // A return from an account provider: the App Link the node sent the browser to.
    private fun deliverReturn(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && data.toString().startsWith(OAuthReturns.REDIRECT_URI))
            oauthReturns.deliver(data.fragment)
    }

    // Leaving the app drops the device keys from memory. Coming back within the Keystore's session
    // window opens them again silently; after it, the phone asks for the screen lock.
    override fun onStop() {
        super.onStop()
        lifecycleScope.launch { nodes.lock() }
    }
}

/**
 * The app's [theme] over [content], with the system bars following it rather than the phone's: light
 * icons on the dark theme. Every activity of the app shows its screens this way.
 */
@Composable
internal fun ComponentActivity.ThemedContent(theme: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    DisposableEffect(dark) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        )
        onDispose {}
    }
    BosonIdentityTheme(darkTheme = dark, content = content)
}
