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

package io.bosonnetwork.identity.core.designsystem.component

import android.content.Context
import io.bosonnetwork.identity.core.designsystem.R
import io.bosonnetwork.identity.core.model.AppError

/** What to tell the user about a failure, in their language. */
fun Throwable.userMessage(context: Context): String = when (this) {
    is AppError.Network -> context.getString(R.string.ds_error_network)
    is AppError.Timeout -> context.getString(R.string.ds_error_timeout)
    is AppError.Unauthorized -> context.getString(R.string.ds_error_unauthorized)
    is AppError.Forbidden -> context.getString(R.string.ds_error_forbidden)
    is AppError.PassphraseRequired -> context.getString(R.string.ds_error_passphrase_required)
    is AppError.RateLimited ->
        if (retryAfterSeconds > 0) context.passphraseLockedMessage(retryAfterSeconds)
        else context.getString(R.string.ds_error_busy)
    is AppError.AuthenticationCancelled -> context.getString(R.string.ds_error_cancelled)
    is AppError.KeyUnavailable -> context.getString(R.string.ds_error_key_unavailable)
    is AppError.ServiceUnavailable -> context.getString(R.string.ds_error_service_unavailable)
    is AppError.ServerError -> context.getString(R.string.ds_error_server)
    else -> message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.ds_error_unknown)
}

/** "Too many wrong passphrases. Try again in N seconds/minutes/hours." */
fun Context.passphraseLockedMessage(retryAfterSeconds: Long): String {
    val res = resources
    val wait = when {
        retryAfterSeconds < 90 -> retryAfterSeconds.toInt().let { res.getQuantityString(R.plurals.ds_wait_seconds, it, it) }
        retryAfterSeconds < 90 * 60 -> ((retryAfterSeconds + 59) / 60).toInt().let { res.getQuantityString(R.plurals.ds_wait_minutes, it, it) }
        else -> ((retryAfterSeconds + 3599) / 3600).toInt().let { res.getQuantityString(R.plurals.ds_wait_hours, it, it) }
    }
    return getString(R.string.ds_error_locked, wait)
}
