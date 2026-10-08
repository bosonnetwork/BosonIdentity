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

package io.bosonnetwork.identity.core.boson

import io.bosonnetwork.director.client.exceptions.DirectorException
import io.bosonnetwork.director.client.exceptions.RateLimitException
import io.bosonnetwork.higgs.exceptions.HiggsException
import io.bosonnetwork.identity.core.model.AppError
import java.io.IOException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * Maps a failure to an [AppError]. The Director client fails a call it got an answer to with a
 * [DirectorException] carrying the HTTP status, and one that got no answer with
 * [DirectorException.NO_HTTP_STATUS]. Anything already an [AppError] passes through.
 */
fun Throwable.toAppError(): AppError = when (val e = unwrapped()) {
    is AppError -> e
    is DirectorException -> when (e.status) {
        DirectorException.NO_HTTP_STATUS -> AppError.Network(e.message, e)
        400 -> AppError.InvalidInput(e.message, e)
        401 -> AppError.Unauthorized(e.message, e)
        403 -> AppError.Forbidden(e.message, e)
        // Gone: a request that ended (an enrollment approved, denied or expired) is no more there to act on.
        404, 410 -> AppError.NotFound(e.message, e)
        408 -> AppError.Timeout(e.message, e)
        409, 412 -> AppError.Conflict(e.message, e)
        428 -> AppError.PassphraseRequired(e.message, e)
        429 -> AppError.RateLimited(e.message, e, (e as? RateLimitException)?.retryAfter ?: 0)
        in 500..599 -> AppError.ServerError(e.message, e)
        else -> AppError.Unknown(e.message, e)
    }
    // The web gateway's client reports the same way, with its own exception type.
    is HiggsException -> when (e.status) {
        HiggsException.NO_HTTP_STATUS -> AppError.Network(e.message, e)
        400 -> AppError.InvalidInput(e.message, e)
        401 -> AppError.Unauthorized(e.message, e)
        403 -> AppError.Forbidden(e.message, e)
        404 -> AppError.NotFound(e.message, e)
        408, 504 -> AppError.Timeout(e.message, e)
        409, 412 -> AppError.Conflict(e.message, e)
        429, 503 -> AppError.RateLimited(e.message, e)
        in 500..599 -> AppError.ServerError(e.message, e)
        else -> AppError.Unknown(e.message, e)
    }
    is IOException -> AppError.Network(e.message, e)
    else -> AppError.Unknown(e.message, e)
}

// A future's failure may arrive wrapped, depending on how it was awaited.
private fun Throwable.unwrapped(): Throwable {
    var e = this
    while ((e is CompletionException || e is ExecutionException) && e.cause != null) e = e.cause!!
    return e
}
