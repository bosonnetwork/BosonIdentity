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

package io.bosonnetwork.identity.core.backup

/** A passkey and the PRF output it gave for the backup's salt. */
class PrfResult(val credentialId: ByteArray, val prf: ByteArray)

/**
 * The phone's passkey provider (Android Credential Manager): creates the backup's passkey and asks it for
 * its PRF output. Both need the user's fingerprint or screen lock, which the provider asks for.
 */
interface PasskeyPrf {
    /** Whether this phone can make a passkey at all. */
    fun isAvailable(): Boolean

    /**
     * Creates a passkey for [PasskeyBackupFormat.RP_ID] for the identity [userId], and gets its PRF output
     * for [salt].
     *
     * @throws PrfUnsupportedException if the provider the user picked doesn't support PRF
     */
    suspend fun create(userId: ByteArray, userName: String, displayName: String, salt: ByteArray): PrfResult

    /** Asks the passkey [credentialId] (or any, when null) for its PRF output for [salt]. */
    suspend fun evaluate(credentialId: ByteArray?, salt: ByteArray): PrfResult
}

/** The provider the user picked can't give a PRF output, so it can't unlock a backup. */
class PrfUnsupportedException(message: String) : Exception(message)

/**
 * Where sealed backups are kept (Android Block Store): end-to-end encrypted with the phone's screen lock,
 * backed up to the user's Google account, and offered back when a new phone is set up.
 */
interface BackupStore {
    /** Whether the store can back up to the cloud at all (it needs a screen lock and a Google account). */
    suspend fun isCloudBackupAvailable(): Boolean

    suspend fun put(key: String, bytes: ByteArray)

    suspend fun get(key: String): ByteArray?

    /** Every backup the store holds, by key. */
    suspend fun all(): Map<String, ByteArray>

    suspend fun delete(key: String)
}
