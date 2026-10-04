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
 * A Boson node the identity uses, through its Director.
 *
 * @property url the Director's base URL, without a trailing slash
 * @property nodeId the node id (base58) that pins the Director's certificate when it is self-signed;
 *   null for a Director with a CA-issued certificate
 */
data class NodeAddress(val url: String, val nodeId: String? = null) {
    init {
        require(url.isNotBlank()) { "Empty node URL" }
    }

    companion object {
        /** Normalizes a typed address: trims it, adds https:// when no scheme is given, drops a trailing slash. */
        fun parse(text: String, nodeId: String? = null): NodeAddress {
            val trimmed = text.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "Empty node URL" }
            val url = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            return NodeAddress(url, nodeId?.trim()?.ifEmpty { null })
        }
    }
}

/**
 * What the app knows about one identity, kept beside its keys in the vault. Nothing here is secret.
 *
 * @property userId the identity's id (base58 of the user key's public key)
 * @property name the name the user gave it, or null
 * @property backupVerified whether the user proved they saved the key; until then the identity
 *   authorizes nothing
 * @property homeNode the node the identity is registered with as a user, or null before registration
 * @property deviceId this app's own device id under the identity, once registered with the home node
 */
data class IdentityInfo(
    val userId: String,
    val name: String? = null,
    val backupVerified: Boolean = false,
    val homeNode: NodeAddress? = null,
    val deviceId: String? = null,
)
