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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.bosonnetwork.identity.core.designsystem.theme.BosonIdentityTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real saveable pane lifecycle: scrolling survives navigation; changed filters start at the top. */
@RunWith(AndroidJUnit4::class)
class FederationScrollStateTest {
    @get:Rule val compose = createComposeRule()
    private val shown = mutableStateOf(true)
    private val query = mutableStateOf("")
    private val role = mutableStateOf("")
    private val status = mutableStateOf("")
    private val refreshes = mutableStateOf(0)

    @Composable
    private fun Pane() {
        val saved = rememberSaveableStateHolder()
        BosonIdentityTheme {
            Surface(Modifier.fillMaxSize()) {
                if (shown.value) saved.SaveableStateProvider("list") {
                    val list = rememberLazyListState()
                    ResetScrollOnChange(list, query.value, role.value, status.value)
                    Column {
                        Text("Refresh " + refreshes.value)
                        LazyColumn(Modifier.height(240.dp).testTag("list"), state = list) {
                            items((0 until 100).toList(), key = { it }) { Text("Row " + it, Modifier.padding(16.dp)) }
                        }
                    }
                } else Text("Other view")
            }
        }
    }

    private fun scrollTo(index: Int) {
        compose.onNodeWithTag("list").performScrollToIndex(index)
        compose.onNodeWithText("Row " + index).assertIsDisplayed()
    }

    @Test
    fun scrollSurvivesViewSwitchAndSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Pane() }
        scrollTo(30)
        compose.runOnIdle { shown.value = false }
        compose.onNodeWithText("Other view").assertIsDisplayed()
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithText("Row 30").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Row 30").assertIsDisplayed()
    }

    @Test
    fun refreshKeepsScrollButQueryOrProposalFiltersResetIt() {
        compose.setContent { Pane() }
        scrollTo(20)
        compose.runOnIdle { refreshes.value++ }
        compose.onNodeWithText("Refresh 1").assertIsDisplayed()
        compose.onNodeWithText("Row 20").assertIsDisplayed()
        compose.runOnIdle { role.value = "offer" }
        compose.onNodeWithText("Row 0").assertIsDisplayed()
        scrollTo(30)
        compose.runOnIdle { shown.value = false }
        compose.onNodeWithText("Other view").assertIsDisplayed()
        compose.runOnIdle { query.value = "node"; status.value = "pending"; shown.value = true }
        compose.onNodeWithText("Row 0").assertIsDisplayed()
    }
}
