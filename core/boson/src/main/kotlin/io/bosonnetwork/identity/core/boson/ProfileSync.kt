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

import io.bosonnetwork.director.client.Avatar
import io.bosonnetwork.director.client.AvatarRefresh
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.Profile
import io.bosonnetwork.director.client.ProfileUpdate
import io.bosonnetwork.director.client.exceptions.ProfileChangedException
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.model.LocalProfile
import io.bosonnetwork.identity.core.model.ProfileFields
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a sync of the profile with the home node did. */
sealed interface SyncOutcome {
    /** The identity has no home node: its profile lives on this phone only. */
    data object NoNode : SyncOutcome

    /** Both sides already agreed. */
    data object UpToDate : SyncOutcome

    /** The node's newer profile was taken. */
    data object Pulled : SyncOutcome

    /** This phone's changes went to the node. */
    data object Pushed : SyncOutcome

    /** Both sides had changed: merged, the node's changes kept for the fields both changed ([kept]). */
    data class Merged(val kept: Set<String>) : SyncOutcome

    /** The account has a passphrase, which pushing a change of the name, bio or email needs. */
    data object NeedsPassphrase : SyncOutcome
}

/**
 * Keeps an identity's profile, which lives on this phone first, in sync with its home node, against the
 * node's profile revision. Reading and writing go through this app's device key; a change of the name, bio
 * or email needs the account passphrase when the account has one.
 *
 * - Only the node moved since the last sync: its profile is taken.
 * - Only this phone changed: the changes are sent, conditional on the revision last synced.
 * - Both: merged field by field. Fields only one side changed take that side's value; fields both changed
 *   keep the node's. Before the first sync, the node's values win and this phone's fill the fields the node
 *   has empty.
 *
 * A change the node refuses because its profile moved meanwhile is synced again, once.
 */
class ProfileSync(
    private val vault: IdentityVault,
    private val nodes: NodeConnections,
    private val avatars: AvatarStore,
) {
    private val mutex = Mutex()

    /** Changes the profile's text fields on this phone, keeping the avatar. Synced separately. */
    suspend fun edit(userId: String, name: String?, bio: String?, email: String?): IdentityInfo = mutex.withLock {
        vault.update(userId) {
            it.copy(profile = it.profile.copy(fields = it.profile.fields.copy(name = name, bio = bio, email = email)))
        }
    }

    /** Sets the avatar on this phone ([image] a JPEG), or removes it ([image] null). Synced separately. */
    suspend fun setAvatar(userId: String, image: ByteArray?): IdentityInfo = mutex.withLock {
        avatars.put(userId, image?.let { Avatar.of(JPEG, it, null, null) })
        vault.update(userId) {
            it.copy(profile = it.profile.copy(fields = it.profile.fields.copy(avatar = image?.let(AvatarStore::digest))))
        }
    }

    /**
     * Syncs the profile of [userId] with its home node. [prompt] unlocks this app's device key if needed;
     * [passphrase] is the account passphrase, when pushing needs it.
     */
    suspend fun sync(userId: String, prompt: AuthPrompt, passphrase: String? = null): SyncOutcome = mutex.withLock {
        try {
            syncOnce(userId, prompt, passphrase)
        } catch (e: AppError.Conflict) {
            // Changed on the node while this sync ran: once more, from what the node has now.
            if (e.cause !is ProfileChangedException) throw e
            syncOnce(userId, prompt, passphrase)
        }
    }

    private suspend fun syncOnce(userId: String, prompt: AuthPrompt, passphrase: String?): SyncOutcome {
        pendingRemoteAvatar.remove(userId)
        val identity = identity(userId)
        if (identity.homeNode == null) return SyncOutcome.NoNode
        val client = nodes.deviceClient(identity, prompt)
        val profile = call { client.profile.await() }
        val local = identity.profile
        val revision = profile.profileRevision

        if (local.revision == revision && !local.dirty) return SyncOutcome.UpToDate

        val remote = try {
            remoteFields(identity, client, profile, local)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The node's new picture couldn't be had. With nothing changed here (or never synced), its
            // text is taken, and the revision kept as it was, so the next sync fetches the picture again.
            if (local.dirty && local.synced != null) throw e
            val text = remoteFields(identity, client, profile, local, withAvatar = false)
            val kept = local.revision
            if (kept == null) {
                // Never synced: the text shows now; the next sync merges as a first one.
                val fields = merge(null, local.fields, text)
                withContext(NonCancellable) { vault.update(userId) { it.copy(profile = it.profile.copy(fields = fields)) } }
            } else {
                store(userId, text, kept, local.synced?.copy(name = text.name, bio = text.bio,
                    email = text.email) ?: text, avatarKnown = false)
            }
            return SyncOutcome.Pulled
        }
        if (!local.dirty) {
            store(userId, remote, revision, remote)
            return SyncOutcome.Pulled
        }

        val base = local.synced
        val merged = merge(base, local.fields, remote)
        val kept = if (base == null) emptySet() else FIELDS.filter { f ->
            f.of(local.fields) != f.of(base) && f.of(remote) != f.of(base) && f.of(local.fields) != f.of(remote)
        }.map { it.name }.toSet()

        if (merged == remote) {
            store(userId, merged, revision, remote)
            return if (kept.isEmpty()) SyncOutcome.Pulled else SyncOutcome.Merged(kept)
        }

        var current = revision
        val update = ProfileUpdate()
        if (merged.name != remote.name) update.name(merged.name)
        if (merged.bio != remote.bio) update.bio(merged.bio)
        if (merged.email != remote.email) update.email(merged.email)
        if (!update.isEmpty) {
            current = try {
                call { client.updateProfile(update.ifRevision(current), passphrase).await() }
            } catch (e: AppError.PassphraseRequired) {
                return SyncOutcome.NeedsPassphrase
            }
        }
        if (merged.avatar != remote.avatar) {
            current = if (merged.avatar == null) {
                call { client.removeAvatar(current).await() }
            } else {
                val image = avatars.held(userId)?.data ?: throw IllegalStateException("The avatar image is missing")
                call { client.changeAvatar(image, JPEG, current).await() }.revision
            }
            // Kept with the node's validators, so the next sync needn't download it.
            if (merged.avatar != null) refreshHeldAvatar(userId, client)
        }
        store(userId, merged, current, merged)
        return if (kept.isEmpty()) SyncOutcome.Pushed else SyncOutcome.Merged(kept)
    }

    // The node's fields; its avatar named by digest, downloaded only when the node's profile moved.
    private suspend fun remoteFields(
        identity: IdentityInfo,
        client: DirectorClient,
        profile: Profile,
        local: LocalProfile,
        withAvatar: Boolean = true,
    ): ProfileFields {
        val avatarDigest = when {
            !withAvatar -> local.fields.avatar
            profile.avatar.orElse(null).isNullOrBlank() -> null
            local.revision == profile.profileRevision -> local.synced?.avatar
            else -> {
                val avatar = call { client.avatar.await() }.orElse(null)
                // The local picture stays until the merge says which one to keep.
                avatar?.let { pendingRemoteAvatar[identity.userId] = it; AvatarStore.digest(it.data) }
            }
        }
        return ProfileFields(
            name = profile.name.orElse(null)?.takeIf { it.isNotBlank() },
            bio = profile.bio.orElse(null)?.takeIf { it.isNotBlank() },
            email = profile.email.orElse(null)?.takeIf { it.isNotBlank() },
            avatar = avatarDigest,
        )
    }

    // The node's avatar downloaded during this sync, until the merge decides whether it is kept.
    private val pendingRemoteAvatar = mutableMapOf<String, Avatar>()

    // The picture and the profile are stored together: a sync cancelled (the screen left, Refresh tapped
    // again) never keeps one without the other.
    private suspend fun store(userId: String, fields: ProfileFields, revision: Long, synced: ProfileFields, avatarKnown: Boolean = true) =
        withContext(NonCancellable) { storeNow(userId, fields, revision, synced, avatarKnown) }

    private suspend fun storeNow(userId: String, fields: ProfileFields, revision: Long, synced: ProfileFields, avatarKnown: Boolean) {
        val remoteAvatar = pendingRemoteAvatar.remove(userId)
        if (!avatarKnown) {
            // The picture on this phone stays as it is.
        } else if (fields.avatar == null) {
            avatars.put(userId, null)
        } else if (remoteAvatar != null && AvatarStore.digest(remoteAvatar.data) == fields.avatar) {
            avatars.put(userId, remoteAvatar)
        }
        vault.update(userId) { it.copy(profile = LocalProfile(fields, revision, synced)) }
    }

    private suspend fun refreshHeldAvatar(userId: String, client: DirectorClient) {
        try {
            val held = avatars.held(userId) ?: return
            val refresh = call { client.refreshAvatar(held).await() }
            if (refresh.status == AvatarRefresh.Status.CHANGED) refresh.avatar.orElse(null)?.let { avatars.put(userId, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Validators are a nicety: the next sync downloads it at worst.
        }
    }

    private fun identity(userId: String): IdentityInfo =
        vault.identities.value.firstOrNull { it.userId == userId } ?: throw AppError.NotFound("No such identity")

    private class Field(val name: String, val of: (ProfileFields) -> String?)

    companion object {
        private const val JPEG = "image/jpeg"

        private val FIELDS = listOf(
            Field("name") { it.name },
            Field("bio") { it.bio },
            Field("email") { it.email },
            Field("avatar") { it.avatar },
        )

        /**
         * Merges [local] and [remote] from their common [base]: a field only one side changed takes that
         * side's value; a field both changed keeps [remote]'s. Without a base (never synced), [remote]'s
         * values win and [local]'s fill the fields [remote] has empty.
         */
        fun merge(base: ProfileFields?, local: ProfileFields, remote: ProfileFields): ProfileFields {
            fun pick(f: Field): String? {
                val l = f.of(local)
                val r = f.of(remote)
                if (base == null) return r ?: l
                return if (r != f.of(base)) r else l
            }
            return ProfileFields(
                name = pick(FIELDS[0]),
                bio = pick(FIELDS[1]),
                email = pick(FIELDS[2]),
                avatar = pick(FIELDS[3]),
            )
        }
    }
}
