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

package io.bosonnetwork.identity.core.model

/**
 * The failures the UI tells apart. Director answers are mapped to these by HTTP status in
 * `core:boson`; the vault raises its own ([KeyUnavailable], [AuthenticationCancelled]).
 */
sealed class AppError(message: String?, cause: Throwable? = null) : Exception(message, cause) {
    /** The Director could not be reached (DNS, socket, TLS). */
    class Network(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** The request timed out, or a pending request expired (HTTP 408). */
    class Timeout(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** The Director refused the credentials (HTTP 401). */
    class Unauthorized(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** Authenticated but not allowed, or a wrong passphrase (HTTP 403). */
    class Forbidden(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** Not found (HTTP 404). */
    class NotFound(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** Already exists or in the wrong state (HTTP 409, 412). */
    class Conflict(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** The account has a passphrase and none was given (HTTP 428). */
    class PassphraseRequired(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /**
     * Rate-limited (HTTP 429). After too many wrong passphrases the Director says how long to wait,
     * in [retryAfterSeconds] (0 when it did not say).
     */
    class RateLimited(
        message: String? = null,
        cause: Throwable? = null,
        val retryAfterSeconds: Long = 0,
    ) : AppError(message, cause)

    /** Invalid input, refused before or by the Director (HTTP 400). */
    class InvalidInput(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** The user dismissed the system authentication prompt; nothing happened. */
    class AuthenticationCancelled(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /**
     * A key in the vault can no longer be unlocked: the Keystore key protecting it is gone (for
     * example after the screen lock was removed). The identity must be restored from its backup.
     */
    class KeyUnavailable(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** Anything else. */
    class Unknown(message: String? = null, cause: Throwable? = null) : AppError(message, cause)
}
