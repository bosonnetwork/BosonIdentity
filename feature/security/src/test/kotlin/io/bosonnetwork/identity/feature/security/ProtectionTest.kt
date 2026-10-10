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

import io.bosonnetwork.identity.core.designsystem.component.Status
import io.bosonnetwork.identity.core.model.IdentityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionTest {
    private val backedUp = IdentityInfo("user", backupVerified = true)

    private fun overview(passphrase: Boolean, recovery: RecoveryView?) =
        SecurityOverview(passphrase, recovery, linked = emptyList(), passkeys = emptyList(), log = null)

    @Test
    fun `a backed-up identity with a passphrase and codes needs nothing`() {
        val issues = protectionIssues(backedUp, overview(true, RecoveryView(0, 0, 8)))
        assertEquals(emptyList<Issue>(), issues)
        assertEquals(Status.OK, issues.worst())
    }

    @Test
    fun `the backup counts before the node has said anything`() {
        assertEquals(listOf(Issue.BACKUP), protectionIssues(backedUp.copy(backupVerified = false), null))
        assertEquals(emptyList<Issue>(), protectionIssues(backedUp, null))
        assertEquals(emptyList<Issue>(), protectionIssues(null, null))
    }

    @Test
    fun `without a passphrase, recovery is not judged`() {
        assertEquals(listOf(Issue.NO_PASSPHRASE), protectionIssues(backedUp, overview(false, RecoveryView(0, 0, 0))))
    }

    @Test
    fun `no way to recover is a problem, and comes first`() {
        val issues = protectionIssues(backedUp.copy(backupVerified = false), overview(true, RecoveryView(0, 0, 0)))
        assertEquals(listOf(Issue.NO_RECOVERY, Issue.BACKUP), issues)
        assertEquals(Status.PROBLEM, issues.worst())
    }

    @Test
    fun `recovery without codes needs attention, and an older node's silence nothing`() {
        assertEquals(listOf(Issue.NO_RECOVERY_CODES), protectionIssues(backedUp, overview(true, RecoveryView(1, 0, 0))))
        assertEquals(Status.ATTENTION, listOf(Issue.NO_RECOVERY_CODES).worst())
        assertEquals(emptyList<Issue>(), protectionIssues(backedUp, overview(true, null)))
    }

    @Test
    fun `events newer than the review are new, none before the log was first read`() {
        val log = listOf(LogEntry(LogKind.DEVICE_ADDED, at = 9), LogEntry(LogKind.SIGNED_IN, at = 4))
        val withLog = overview(true, RecoveryView(0, 0, 8)).copy(log = log)
        assertEquals(listOf(Issue.NEW_LOG_EVENTS), protectionIssues(backedUp.copy(logReviewedAt = 5), withLog))
        assertEquals(emptyList<Issue>(), protectionIssues(backedUp.copy(logReviewedAt = 9), withLog))
        assertEquals(emptyList<Issue>(), protectionIssues(backedUp, withLog))
        assertEquals(1, newLogEvents(backedUp.copy(logReviewedAt = 5), log).size)
    }
}
