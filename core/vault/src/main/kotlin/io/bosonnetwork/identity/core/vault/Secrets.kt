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

/** Overwrites the array with zeros: secrets are wiped as soon as they are no longer needed. */
fun ByteArray.wipe() = fill(0)

/**
 * A secret encrypted under a Keystore key: the AES-GCM [iv] the Keystore chose and the [data]
 * (ciphertext with its tag). Neither is secret.
 */
class Sealed(val iv: ByteArray, val data: ByteArray)

/** How a Keystore key that protects a secret may be used. */
enum class Protection {
    /** Every use needs a fresh confirmation by the user: the user key, the root of the identity. */
    PER_USE,

    /** Usable for a while after the user unlocked the phone or the app: this app's device key. */
    SESSION,
}

/** The text of the system authentication prompt; the caller words it for the action at hand. */
data class AuthPrompt(val title: String, val subtitle: String? = null)
