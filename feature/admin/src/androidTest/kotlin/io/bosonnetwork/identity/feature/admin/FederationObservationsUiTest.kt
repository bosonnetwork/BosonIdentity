/*
 * Copyright (c) 2026 -      bosonnetwork.io
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

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.bosonnetwork.Id
import io.bosonnetwork.director.client.FederatedHost
import io.bosonnetwork.director.client.FederatedNodeHealth
import io.bosonnetwork.identity.core.designsystem.theme.BosonIdentityTheme
import io.bosonnetwork.json.Json
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated presentation fixtures; no admin session, vault or network client is opened. */
@RunWith(AndroidJUnit4::class)
class FederationObservationsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun health(proposable: Boolean, failing: Boolean = true, unknown: Boolean = false): FederatedNodeHealth {
        val now = System.currentTimeMillis()
        return Json.parse(Json.toString(mapOf(
            "nodeId" to Id.of("11111111111111111111111111111111").toString(),
            "lastSeenAt" to now - 1000, "lastSuccessAt" to now - 3000, "lastFailureAt" to now - 2000,
            "lastFailure" to if (unknown) "FUTURE" else "TIMEOUT", "lastFailureDetail" to "Remote exchange timed out; retry is still possible.",
            "consecutiveFailures" to if (failing) 2 else 0, "lastHour" to mapOf("SUCCESS" to 4, "TIMEOUT" to 2, "FUTURE" to 9),
            "demand" to 3, "lastDemandAt" to now, "lastDemandReason" to if (unknown) "FUTURE" else "NO_RELATIONSHIP",
            "proposable" to proposable,
        )), FederatedNodeHealth::class.java)
    }

    private fun host(address: String?, verified: Int = 4, failed: Int = 2): FederatedHost = Json.parse(Json.toString(mapOf(
        "host" to address, "verifiedNodes" to verified, "failedNodes" to failed,
        "notFederatingNodes" to 3, "lastCheckedAt" to System.currentTimeMillis(),
    )), FederatedHost::class.java)

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.context.externalCacheDir, "federation-observations")
        check(directory.mkdirs() || directory.isDirectory)
        // Compose idleness does not include the Activity window's entry animation.
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun unproposableActivityHasNoProposalAction() {
        compose.setContent {
            BosonIdentityTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column { ActivityRow(health(false), onClick = {}, onPropose = { error("Not proposable") }) }
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.adm_fed_activity_actions)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.adm_fed_hour_success, 4)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.adm_fed_hour_failures, 2)).assertExists()
        screenshot("activity-failing")
    }

    @Test
    fun proposableActivityOffersOneExplicitProposalAction() {
        var proposals = 0
        compose.setContent {
            BosonIdentityTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column { ActivityRow(health(true, failing = false), onClick = {}, onPropose = { proposals++ }) }
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.adm_fed_activity_actions)).performClick()
        compose.onNodeWithText(context.getString(R.string.adm_fed_propose)).performClick()
        compose.runOnIdle { assertEquals(1, proposals) }
        screenshot("activity-proposable")
    }

    @Test
    fun activityDetailsKeepUnknownOutcomesAndReasonsNeutral() {
        compose.setContent {
            BosonIdentityTheme {
                ActivityDetailSheet(health(false, unknown = true), onDismiss = {}, onPropose = { error("Not proposable") })
            }
        }
        compose.onAllNodesWithText(context.getString(R.string.adm_fed_unknown)).assertCountEquals(2)
        compose.onNodeWithText(context.getString(R.string.adm_fed_propose)).assertDoesNotExist()
        compose.onNodeWithText("Remote exchange timed out; retry is still possible.").assertExists()
        screenshot("activity-details")
    }

    @Test
    fun hostCountsRemainIndependentAndIpv6WrapsAtNarrowWidth() {
        compose.setContent {
            BosonIdentityTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.width(320.dp)) { HostRow(host("2001:db8:ffff:ffff::/64")) }
                }
            }
        }
        compose.onNodeWithText("2001:db8:ffff:ffff::/64").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.adm_fed_host_verified, 4)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.adm_fed_host_failed, 2)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.adm_fed_host_not_federating, 3)).assertExists()
        screenshot("host-ipv6-dark")
    }

    @Test
    fun unresolvedHostUsesALocalizedLabel() {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val chinese = context.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides chinese, LocalConfiguration provides configuration) {
                BosonIdentityTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column { HostRow(host(null, verified = 0, failed = 5)) }
                    }
                }
            }
        }
        compose.onNodeWithText(chinese.getString(R.string.adm_fed_unresolved_host)).assertExists()
        compose.onNodeWithText(UNRESOLVED_HOST_QUERY).assertDoesNotExist()
        screenshot("host-unresolved-chinese")
    }

    @Test
    fun hostWithZeroCountsHasNoInventedSuccess() {
        val record = Json.parse("""{"host":"198.51.100.8","verifiedNodes":0,"failedNodes":0,"notFederatingNodes":0,"lastCheckedAt":0}""", FederatedHost::class.java)
        compose.setContent { BosonIdentityTheme { Surface(Modifier.fillMaxSize()) { Column { HostRow(record) } } } }
        compose.onNodeWithContentDescription(context.getString(R.string.adm_fed_host_verified, 0)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.adm_fed_host_failed, 0)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.adm_fed_host_not_federating, 0)).assertExists()
        compose.onNodeWithText(context.getString(R.string.adm_fed_host_checked, context.getString(R.string.adm_fed_unknown))).assertExists()
    }
}
