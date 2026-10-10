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
 * What an identity says about itself; every field is optional. The email is the user's, kept with the
 * profile and synced to the home node, but never published in the public card.
 *
 * @property avatar the SHA-256 (hex) of the avatar image, which the avatar store keeps; null for none.
 *   Comparing digests tells whether two profiles show the same picture.
 */
data class ProfileFields(
    val name: String? = null,
    val bio: String? = null,
    val email: String? = null,
    val avatar: String? = null,
)

/**
 * The identity's profile as this phone keeps it: the vault is where it lives first, the home node keeps
 * a synced copy.
 *
 * @property fields the profile as it is on this phone
 * @property revision the home node's profile revision last synced; null without a home node, or before
 *   the first sync
 * @property synced the fields as the home node had them at [revision]: the base a merge compares both
 *   sides with; null before the first sync
 */
data class LocalProfile(
    val fields: ProfileFields = ProfileFields(),
    val revision: Long? = null,
    val synced: ProfileFields? = null,
) {
    /** Whether this phone changed the profile since the last sync (always, before the first one). */
    val dirty: Boolean
        get() = synced == null || fields != synced
}

/**
 * What the app knows about one identity, kept beside its keys in the vault. Nothing here is secret.
 *
 * @property userId the identity's id (base58 of the user key's public key)
 * @property profile the identity's profile, kept here first and synced with the home node
 * @property backupVerified whether the user proved they saved the key; until then the identity
 *   authorizes nothing
 * @property homeNode the node the identity is registered with as a user, or null before registration
 * @property deviceId this app's own device id under the identity, once registered with the home node
 * @property administers whether the identity proved it administers its home node, by opening admin
 *   mode there; remembered so the Dashboard stays while the node's profile can't be read
 * @property cardVersion the version of the public card last published from this phone; kept after the
 *   card is withdrawn, so versions never go back
 * @property cardRevision the profile revision the published card was made from; null when the card is
 *   not published from this phone. A profile at a newer revision means the card is out of date.
 * @property logReviewedAt the time of the newest security log event the user reviewed on this phone;
 *   newer ones are new. Null until the log was first read, when the events then there count as reviewed.
 */
data class IdentityInfo(
    val userId: String,
    val profile: LocalProfile = LocalProfile(),
    val backupVerified: Boolean = false,
    val homeNode: NodeAddress? = null,
    val deviceId: String? = null,
    val administers: Boolean = false,
    val cardVersion: Int? = null,
    val cardRevision: Long? = null,
    val logReviewedAt: Long? = null,
) {
    /** The name the identity goes by, or null. */
    val name: String?
        get() = profile.fields.name
}
