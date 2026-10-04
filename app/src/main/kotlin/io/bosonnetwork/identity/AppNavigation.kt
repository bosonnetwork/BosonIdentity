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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.feature.identity.IdentityScreen
import io.bosonnetwork.identity.feature.onboarding.BackupScreen
import io.bosonnetwork.identity.feature.onboarding.JoinNodeScreen
import io.bosonnetwork.identity.feature.onboarding.JoinNodeViewModel
import io.bosonnetwork.identity.feature.onboarding.OnboardingScreen

private object Routes {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val JOIN = "join/{${JoinNodeViewModel.ARG_USER_ID}}?first={first}"
    const val BACKUP = "backup/{${JoinNodeViewModel.ARG_USER_ID}}"

    fun join(userId: String, first: Boolean) = "join/$userId?first=$first"
    fun backup(userId: String) = "backup/$userId"
}

@Composable
fun AppNavigation(vault: IdentityVault) {
    var start by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vault.load()
        start = if (vault.identities.value.isEmpty()) Routes.WELCOME else Routes.HOME
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        val startRoute = start
        if (startRoute == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Surface
        }
        val nav = rememberNavController()
        NavHost(navController = nav, startDestination = startRoute) {
            composable(Routes.WELCOME) {
                OnboardingScreen(onFinished = { userId ->
                    nav.navigate(Routes.join(userId, first = true)) { popUpTo(Routes.WELCOME) { inclusive = true } }
                })
            }
            composable(
                Routes.JOIN,
                arguments = listOf(
                    navArgument(JoinNodeViewModel.ARG_USER_ID) { type = NavType.StringType },
                    navArgument("first") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                val first = entry.arguments?.getBoolean("first") == true
                val leave: () -> Unit = {
                    if (first) {
                        nav.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                    } else {
                        nav.popBackStack()
                    }
                }
                JoinNodeScreen(onDone = leave, onSkip = leave)
            }
            composable(
                Routes.BACKUP,
                arguments = listOf(navArgument(JoinNodeViewModel.ARG_USER_ID) { type = NavType.StringType }),
            ) {
                BackupScreen(onDone = { nav.popBackStack() }, onCancel = { nav.popBackStack() })
            }
            composable(Routes.HOME) {
                IdentityScreen(
                    onBackup = { nav.navigate(Routes.backup(it)) },
                    onJoinNode = { nav.navigate(Routes.join(it, first = false)) },
                )
            }
        }
    }
}
