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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.AdminAccess
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.feature.account.AccountScreen
import io.bosonnetwork.identity.feature.admin.AdminScreen
import io.bosonnetwork.identity.feature.devices.ApproveDeviceScreen
import io.bosonnetwork.identity.feature.devices.ApproveSignInScreen
import io.bosonnetwork.identity.feature.devices.ApproveSignInViewModel
import io.bosonnetwork.identity.feature.devices.ApproveDeviceViewModel
import io.bosonnetwork.identity.feature.devices.DevicesScreen
import io.bosonnetwork.identity.feature.identity.IdentityScreen
import io.bosonnetwork.identity.feature.onboarding.BackupScreen
import io.bosonnetwork.identity.feature.onboarding.JoinNodeScreen
import io.bosonnetwork.identity.feature.onboarding.JoinNodeViewModel
import io.bosonnetwork.identity.feature.onboarding.MoveFromOldPhoneScreen
import io.bosonnetwork.identity.feature.onboarding.OnboardingScreen
import io.bosonnetwork.identity.feature.security.SecurityScreen
import kotlinx.coroutines.launch

private object Routes {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val MOVE = "move"
    const val JOIN = "join/{${JoinNodeViewModel.ARG_USER_ID}}?first={first}"
    const val BACKUP = "backup/{${JoinNodeViewModel.ARG_USER_ID}}"
    const val APPROVE = "approve/{${ApproveDeviceViewModel.ARG_USER_ID}}"
    const val SIGN_IN = "signin/{${ApproveDeviceViewModel.ARG_USER_ID}}/{${ApproveSignInViewModel.ARG_REQUEST_ID}}"

    fun join(userId: String, first: Boolean) = "join/$userId?first=$first"
    fun backup(userId: String) = "backup/$userId"
    fun approve(userId: String) = "approve/$userId"
    fun signIn(userId: String, requestId: String) = "signin/$userId/$requestId"

    /** Set on the tabs' entry when a device was approved, so the device list is asked for again. */
    const val DEVICES_CHANGED = "devices_changed"

    /** The Admin tab's route. */
    const val ADMIN_TAB = "admin"
}

/**
 * The app's screens. Onboarding, moving from the old phone, joining a node, the backup and approving a
 * device are full-screen routes; everything else is in the tabs of [Routes.HOME]. Onboarding is also how
 * an identity is added: opened from the switcher, it sits above the tabs, so Back from its first step
 * returns to them.
 */
@Composable
fun AppNavigation(vault: IdentityVault, activeIdentity: ActiveIdentity, adminAccess: AdminAccess) {
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
        val scope = rememberCoroutineScope()
        NavHost(navController = nav, startDestination = startRoute) {
            composable(Routes.WELCOME) {
                OnboardingScreen(
                    onFinished = { userId ->
                        // A new identity, the first or one more, becomes the one in use.
                        scope.launch { activeIdentity.select(userId) }
                        nav.navigate(Routes.join(userId, first = true)) { popUpTo(Routes.WELCOME) { inclusive = true } }
                    },
                    onMoveFromOldPhone = { nav.navigate(Routes.MOVE) },
                )
            }
            composable(Routes.MOVE) {
                MoveFromOldPhoneScreen(
                    onDone = { userId ->
                        // The moved identity is already one of the node's devices: no joining step.
                        scope.launch { activeIdentity.select(userId) }
                        nav.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                    },
                    onCancel = { nav.popBackStack() },
                )
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
            composable(
                Routes.APPROVE,
                arguments = listOf(navArgument(ApproveDeviceViewModel.ARG_USER_ID) { type = NavType.StringType }),
            ) {
                ApproveDeviceScreen(
                    onDone = {
                        nav.previousBackStackEntry?.savedStateHandle?.set(Routes.DEVICES_CHANGED, true)
                        nav.popBackStack()
                    },
                    onClose = { nav.popBackStack() },
                    onBackup = { nav.navigate(Routes.backup(it)) },
                    // A web page's sign-in code, scanned here: its own screen replaces this one.
                    onSignIn = { requestId ->
                        val userId = it.arguments?.getString(ApproveDeviceViewModel.ARG_USER_ID).orEmpty()
                        nav.navigate(Routes.signIn(userId, requestId)) { popUpTo(Routes.APPROVE) { inclusive = true } }
                    },
                )
            }
            composable(
                Routes.SIGN_IN,
                arguments = listOf(
                    navArgument(ApproveDeviceViewModel.ARG_USER_ID) { type = NavType.StringType },
                    navArgument(ApproveSignInViewModel.ARG_REQUEST_ID) { type = NavType.StringType },
                ),
            ) {
                ApproveSignInScreen(
                    onClose = { nav.popBackStack() },
                    onBackup = { nav.navigate(Routes.backup(it)) },
                )
            }
            composable(Routes.HOME) {
                val identities by vault.identities.collectAsStateWithLifecycle()
                val active by activeIdentity.active.collectAsStateWithLifecycle(initialValue = null)
                var switching by rememberSaveable { mutableStateOf(false) }
                var requestedTab by remember { mutableStateOf<String?>(null) }

                // The Admin tab: for an identity that opened admin mode at its home node, or whose
                // account there is marked as an administrator (the node's root user may not be).
                val profileAdmins by adminAccess.profileAdmins.collectAsStateWithLifecycle()
                val showAdmin = AdminAccess.shows(active, profileAdmins)
                HomeTabs(
                    tabs = remember(nav) { homeTabs(nav, onAdminOpened = { requestedTab = Routes.ADMIN_TAB }) },
                    showAdmin = showAdmin,
                    identityButton = { IdentityButton(active, onClick = { switching = true }) },
                    requested = requestedTab,
                    onRequestDone = { requestedTab = null },
                )
                if (switching) {
                    IdentitySwitcherSheet(
                        identities = identities,
                        activeUserId = active?.userId,
                        onSelect = { userId -> scope.launch { activeIdentity.select(userId) } },
                        onAdd = { nav.navigate(Routes.WELCOME) },
                        onDismiss = { switching = false },
                    )
                }
            }
        }
    }
}

/**
 * The tabs, in their order. Admin, last, shows only to an identity that administers its home node;
 * [onAdminOpened] asks to show it once admin mode was opened from Account.
 */
private fun homeTabs(nav: NavController, onAdminOpened: () -> Unit): List<HomeTab> = listOf(
    HomeTab("identity", R.string.app_nav_identity, Icons.Filled.Fingerprint) { identityButton ->
        IdentityScreen(
            onBackup = { nav.navigate(Routes.backup(it)) },
            onJoinNode = { nav.navigate(Routes.join(it, first = false)) },
            navigationIcon = identityButton,
        )
    },
    HomeTab("devices", R.string.app_nav_devices, Icons.Filled.Devices) { identityButton ->
        // A device approved on its own screen shows up when the user comes back.
        val home = remember(nav) { nav.getBackStackEntry(Routes.HOME) }
        val changed by home.savedStateHandle.getStateFlow(Routes.DEVICES_CHANGED, false).collectAsStateWithLifecycle()
        DevicesScreen(
            onApprove = { nav.navigate(Routes.approve(it)) },
            onBackup = { nav.navigate(Routes.backup(it)) },
            onJoinNode = { nav.navigate(Routes.join(it, first = false)) },
            changed = changed,
            onChangeSeen = { home.savedStateHandle[Routes.DEVICES_CHANGED] = false },
            navigationIcon = identityButton,
        )
    },
    HomeTab("security", R.string.app_nav_security, Icons.Filled.Shield) { identityButton ->
        SecurityScreen(
            onBackup = { nav.navigate(Routes.backup(it)) },
            onJoinNode = { nav.navigate(Routes.join(it, first = false)) },
            navigationIcon = identityButton,
        )
    },
    HomeTab("account", R.string.app_nav_account, Icons.Filled.AccountCircle) { identityButton ->
        AccountScreen(
            onJoinNode = { nav.navigate(Routes.join(it, first = false)) },
            onNoIdentityLeft = { nav.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } } },
            onAdminOpened = onAdminOpened,
            navigationIcon = identityButton,
        )
    },
    HomeTab(Routes.ADMIN_TAB, R.string.app_nav_admin, Icons.Filled.AdminPanelSettings, adminOnly = true) { identityButton ->
        AdminScreen(navigationIcon = identityButton)
    },
)
