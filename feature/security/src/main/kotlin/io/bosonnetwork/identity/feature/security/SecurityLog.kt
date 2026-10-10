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

import io.bosonnetwork.director.client.SecurityEvent
import java.util.Locale

/** What a security log entry records. */
enum class LogKind {
    PASSPHRASE_SET,
    PASSPHRASE_CHANGED,
    PASSPHRASE_REMOVED,
    PASSPHRASE_RESET,
    RECOVERY_CODES,
    DEVICE_ADDED,
    DEVICE_REMOVED,
    ACCOUNT_LINKED,
    ACCOUNT_UNLINKED,
    SIGNED_IN,

    /** Made an administrator of the node, by enrollment: [LogEntry.subject] is `root` or the administrator's id. */
    ADMIN_ENROLLED,

    /** A kind this version doesn't know; the entry shows the node's own word for it. */
    OTHER,
}

/** How a forgotten passphrase was reset. */
enum class ResetMethod { RECOVERY_CODE, ACCOUNT, PASSKEY, UNKNOWN }

/**
 * One entry of the security log, ready to be read as a sentence.
 *
 * @property subject what the entry is about: the device's name, the account provider's name, the web
 *   app signed in to (`portal` or `admin`), or, for [LogKind.OTHER], the node's word for the kind
 * @property passkey for a device added or removed: whether it is a browser passkey
 * @property resetMethod for a reset passphrase: how it was reset
 * @property at when it happened, in epoch milliseconds
 * @property address where it came from, as the node saw it
 */
data class LogEntry(
    val kind: LogKind,
    val subject: String? = null,
    val passkey: Boolean = false,
    val resetMethod: ResetMethod? = null,
    val at: Long,
    val address: String? = null,
)

/**
 * Reads a security event the way the node records it: the kind, and a method whose form depends on
 * the kind (`recovery-code`, `oauth:<provider>` or `passkey:<device>` for a reset; `<kind>: <name>` for
 * a device; the provider for a linked account; the web app for a sign-in).
 */
fun logEntry(event: SecurityEvent): LogEntry {
    val method = event.method.trim()
    val at = event.at
    val address = event.address.orElse(null)?.takeIf { it.isNotBlank() }
    return when (event.kind) {
        "set" -> LogEntry(LogKind.PASSPHRASE_SET, at = at, address = address)
        "changed" -> LogEntry(LogKind.PASSPHRASE_CHANGED, at = at, address = address)
        "removed" -> LogEntry(LogKind.PASSPHRASE_REMOVED, at = at, address = address)
        "recovery-codes" -> LogEntry(LogKind.RECOVERY_CODES, at = at, address = address)
        "reset" -> {
            val (how, what) = method.split(':', limit = 2).let { it[0].trim() to it.getOrNull(1)?.trim() }
            when (how) {
                "recovery-code" -> LogEntry(LogKind.PASSPHRASE_RESET, resetMethod = ResetMethod.RECOVERY_CODE, at = at, address = address)
                "oauth" -> LogEntry(
                    LogKind.PASSPHRASE_RESET,
                    subject = what?.takeIf { it.isNotEmpty() }?.let(::providerName),
                    resetMethod = ResetMethod.ACCOUNT,
                    at = at,
                    address = address,
                )
                "passkey" -> LogEntry(LogKind.PASSPHRASE_RESET, resetMethod = ResetMethod.PASSKEY, at = at, address = address)
                else -> LogEntry(LogKind.PASSPHRASE_RESET, resetMethod = ResetMethod.UNKNOWN, at = at, address = address)
            }
        }
        "device-added", "device-removed" -> {
            val colon = method.indexOf(": ")
            val deviceKind = if (colon > 0) method.substring(0, colon) else ""
            val name = if (colon > 0) method.substring(colon + 2).trim() else method
            LogEntry(
                if (event.kind == "device-added") LogKind.DEVICE_ADDED else LogKind.DEVICE_REMOVED,
                subject = name.ifEmpty { null },
                passkey = deviceKind == "passkey",
                at = at,
                address = address,
            )
        }
        "linked", "unlinked" -> LogEntry(
            if (event.kind == "linked") LogKind.ACCOUNT_LINKED else LogKind.ACCOUNT_UNLINKED,
            subject = method.ifEmpty { null }?.let(::providerName),
            at = at,
            address = address,
        )
        "signed-in" -> LogEntry(LogKind.SIGNED_IN, subject = method.ifEmpty { null }, at = at, address = address)
        // "by root", or "by administrator <id>".
        "admin-enrolled" -> LogEntry(
            LogKind.ADMIN_ENROLLED,
            subject = method.removePrefix("by ").removePrefix("administrator ").trim().ifEmpty { null },
            at = at,
            address = address,
        )
        else -> LogEntry(LogKind.OTHER, subject = event.kind, at = at, address = address)
    }
}

/** An account provider's name as people know it, from the id the node uses ("github" is "GitHub"). */
fun providerName(id: String): String = KNOWN_PROVIDERS[id.lowercase(Locale.ROOT)]
    ?: id.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

private val KNOWN_PROVIDERS = mapOf(
    "github" to "GitHub",
    "gitlab" to "GitLab",
    "google" to "Google",
    "microsoft" to "Microsoft",
    "apple" to "Apple",
    "facebook" to "Facebook",
    "linkedin" to "LinkedIn",
)
