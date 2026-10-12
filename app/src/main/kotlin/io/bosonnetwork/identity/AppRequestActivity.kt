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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import io.bosonnetwork.director.client.PairingCode
import io.bosonnetwork.identity.core.backup.CredentialManagerPrf
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.vault.ActivityAuthenticator
import io.bosonnetwork.identity.feature.devices.ApproveDeviceScreen
import io.bosonnetwork.identity.feature.devices.ApproveDeviceViewModel
import io.bosonnetwork.identity.feature.devices.ApproveStep
import io.bosonnetwork.identity.feature.onboarding.BackupScreen
import io.bosonnetwork.identity.feature.onboarding.JoinNodeViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Answers other apps' requests ([AppRequests]): sharing an identity's home node, and approving the app
 * as a new device of an identity. Any app may ask; the user sees which one (its name and package, as
 * Android reports the caller) and decides. A request not started for a result has no caller to show,
 * and is refused.
 */
@AndroidEntryPoint
class AppRequestActivity : FragmentActivity() {

    @Inject lateinit var authenticator: ActivityAuthenticator
    @Inject lateinit var passkeyPrf: CredentialManagerPrf
    @Inject lateinit var nodes: NodeConnections

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppSettings.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Leaving any other way (Back, the task closed) answers nothing.
        setResult(Activity.RESULT_CANCELED, error(AppRequests.ERROR_CANCELED))
        val request = readRequest(intent)
        if (request == null) {
            finishWith(Activity.RESULT_CANCELED, error(AppRequests.ERROR_INVALID_REQUEST))
            return
        }
        authenticator.attach(this)
        passkeyPrf.attach(this)
        enableEdgeToEdge()
        setContent {
            ThemedContent(AppSettings.theme(this)) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    AppRequestFlow(request)
                }
            }
        }
    }

    // The screen lock is asked over the activity in front.
    override fun onResume() {
        super.onResume()
        authenticator.attach(this)
        passkeyPrf.attach(this)
    }

    // As the app's own activity does: leaving drops the device keys from memory.
    override fun onStop() {
        super.onStop()
        lifecycleScope.launch { nodes.lock() }
    }

    private fun readRequest(intent: Intent): AppRequest? {
        val caller = callingPackage ?: return null
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(caller, 0)).toString()
        }.getOrNull()?.ifBlank { null } ?: caller
        val userId = intent.getStringExtra(AppRequests.EXTRA_USER_ID)?.trim()?.ifEmpty { null }
        return when (intent.action) {
            AppRequests.ACTION_GET_HOME_NODE -> AppRequest.HomeNode(label, caller, userId)
            AppRequests.ACTION_APPROVE_DEVICE -> {
                val code = intent.getStringExtra(AppRequests.EXTRA_PAIRING_CODE)?.trim() ?: return null
                // Only a pairing code: nothing else this app reads is approved this way.
                runCatching { PairingCode.parse(code) }.getOrNull() ?: return null
                AppRequest.ApproveDevice(label, caller, userId, code)
            }
            else -> null
        }
    }

    private fun error(reason: String) = Intent().putExtra(AppRequests.EXTRA_ERROR, reason)

    private fun finishWith(resultCode: Int, data: Intent) {
        setResult(resultCode, data)
        finish()
    }

    private object Routes {
        const val CHOOSE = "choose"
        const val APPROVE = "approve/{${ApproveDeviceViewModel.ARG_USER_ID}}?" +
            "${ApproveDeviceViewModel.ARG_CODE}={${ApproveDeviceViewModel.ARG_CODE}}&" +
            "${ApproveDeviceViewModel.ARG_REQUESTER_PACKAGE}={${ApproveDeviceViewModel.ARG_REQUESTER_PACKAGE}}&" +
            "${ApproveDeviceViewModel.ARG_REQUESTER_LABEL}={${ApproveDeviceViewModel.ARG_REQUESTER_LABEL}}"
        const val BACKUP = "backup/{${JoinNodeViewModel.ARG_USER_ID}}"

        // The code holds ':' and '/', and a label anything: each encoded, as one query value.
        fun approve(userId: String, request: AppRequest.ApproveDevice) =
            "approve/$userId?${ApproveDeviceViewModel.ARG_CODE}=${Uri.encode(request.code)}&" +
                "${ApproveDeviceViewModel.ARG_REQUESTER_PACKAGE}=${Uri.encode(request.requesterPackage)}&" +
                "${ApproveDeviceViewModel.ARG_REQUESTER_LABEL}=${Uri.encode(request.requesterLabel)}"

        fun backup(userId: String) = "backup/$userId"
    }

    @Composable
    private fun AppRequestFlow(request: AppRequest) {
        val viewModel: AppRequestViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val avatars by viewModel.avatars.collectAsStateWithLifecycle()
        LaunchedEffect(request) { viewModel.start(request) }

        val nav = rememberNavController()
        // An approval for an identity known without asking goes straight to it.
        LaunchedEffect(state.direct) {
            val userId = state.direct ?: return@LaunchedEffect
            if (request is AppRequest.ApproveDevice) {
                nav.navigate(Routes.approve(userId, request)) { popUpTo(Routes.CHOOSE) { inclusive = true } }
            }
        }

        NavHost(navController = nav, startDestination = Routes.CHOOSE) {
            composable(Routes.CHOOSE) {
                AppRequestScreen(
                    request = request,
                    state = state,
                    avatars = avatars,
                    onSelect = viewModel::select,
                    onConfirm = {
                        when (request) {
                            is AppRequest.HomeNode -> viewModel.shareHomeNode { userId, code ->
                                finishWith(
                                    Activity.RESULT_OK,
                                    Intent().putExtra(AppRequests.EXTRA_USER_ID, userId).putExtra(AppRequests.EXTRA_HOME_NODE, code),
                                )
                            }
                            is AppRequest.ApproveDevice -> state.selected?.let { nav.navigate(Routes.approve(it, request)) }
                        }
                    },
                    onClose = { finishWith(Activity.RESULT_CANCELED, error(state.unanswerable ?: AppRequests.ERROR_CANCELED)) },
                )
            }
            composable(
                Routes.APPROVE,
                arguments = listOf(
                    navArgument(ApproveDeviceViewModel.ARG_USER_ID) { type = NavType.StringType },
                    navArgument(ApproveDeviceViewModel.ARG_CODE) { type = NavType.StringType },
                    navArgument(ApproveDeviceViewModel.ARG_REQUESTER_PACKAGE) { type = NavType.StringType },
                    navArgument(ApproveDeviceViewModel.ARG_REQUESTER_LABEL) { type = NavType.StringType },
                ),
            ) { entry ->
                val userId = entry.arguments?.getString(ApproveDeviceViewModel.ARG_USER_ID).orEmpty()
                val approval: ApproveDeviceViewModel = hiltViewModel()
                ApproveDeviceScreen(
                    onDone = { finishWith(Activity.RESULT_OK, Intent().putExtra(AppRequests.EXTRA_USER_ID, userId)) },
                    onClose = {
                        val s = approval.state.value
                        val reason = when {
                            s.step == ApproveStep.DENIED -> AppRequests.ERROR_DENIED
                            s.step == ApproveStep.EXPIRED || s.expired -> AppRequests.ERROR_EXPIRED
                            else -> AppRequests.ERROR_CANCELED
                        }
                        finishWith(Activity.RESULT_CANCELED, error(reason))
                    },
                    onBackup = { nav.navigate(Routes.backup(it)) },
                    viewModel = approval,
                )
            }
            composable(
                Routes.BACKUP,
                arguments = listOf(navArgument(JoinNodeViewModel.ARG_USER_ID) { type = NavType.StringType }),
            ) {
                BackupScreen(onDone = { nav.popBackStack() }, onCancel = { nav.popBackStack() })
            }
        }
    }
}
