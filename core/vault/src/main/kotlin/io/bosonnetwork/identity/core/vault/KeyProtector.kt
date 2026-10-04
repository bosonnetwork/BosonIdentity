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

package io.bosonnetwork.identity.core.vault

/**
 * Encrypts and decrypts small secrets under Keystore keys, asking the user to authenticate as each
 * key's [Protection] requires. The keys never leave the Keystore; only the [Sealed] results are
 * stored, by the vault.
 */
interface KeyProtector {
    /** Whether secrets can be protected at all: the phone needs a secure lock screen. */
    fun isAvailable(): Boolean

    /** Encrypts [plaintext] under the key [alias], creating that key with [protection] if needed. */
    suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt): Sealed

    /**
     * Decrypts [sealed] with the key [alias].
     *
     * @throws io.bosonnetwork.identity.core.model.AppError.KeyUnavailable if the key is gone or invalidated
     * @throws io.bosonnetwork.identity.core.model.AppError.AuthenticationCancelled if the user dismissed the prompt
     */
    suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt): ByteArray

    /** Deletes the key [alias]; whatever it sealed can no longer be opened. */
    fun delete(alias: String)
}
