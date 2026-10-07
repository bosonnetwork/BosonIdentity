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

/** Something about the identity's protection that needs the user, most urgent first. */
enum class Issue(val status: Status) {
    /** No checked copy of the key: losing the phone loses the identity, and it authorizes nothing. */
    BACKUP(Status.ATTENTION),

    /** A forgotten passphrase can't be reset. */
    NO_RECOVERY(Status.PROBLEM),

    /** No account passphrase: a leaked key is enough to change the account. */
    NO_PASSPHRASE(Status.ATTENTION),

    /** Recovery works through linked accounts or passkeys only, without codes kept apart from them. */
    NO_RECOVERY_CODES(Status.ATTENTION),
}

/**
 * What needs the user, from what is known: the key's backup always, the account's protections once
 * the node said them ([overview] null until then). The summary, the section headers and the tab's
 * badge all read this, so they never disagree.
 */
fun protectionIssues(identity: IdentityInfo?, overview: SecurityOverview?): List<Issue> {
    if (identity == null) return emptyList()
    val issues = mutableListOf<Issue>()
    if (!identity.backupVerified) issues += Issue.BACKUP
    if (overview != null) {
        if (!overview.passphraseProtected) {
            issues += Issue.NO_PASSPHRASE
        } else {
            val recovery = overview.recovery
            when {
                // Older nodes don't say; nothing to warn about then.
                recovery == null -> Unit
                !recovery.any -> issues += Issue.NO_RECOVERY
                recovery.recoveryCodes == 0 -> issues += Issue.NO_RECOVERY_CODES
            }
        }
    }
    return issues.sortedBy { it.status != Status.PROBLEM }
}

/** The worst of [issues]: what the tab's badge and the summary show. */
fun List<Issue>.worst(): Status = when {
    any { it.status == Status.PROBLEM } -> Status.PROBLEM
    isNotEmpty() -> Status.ATTENTION
    else -> Status.OK
}
