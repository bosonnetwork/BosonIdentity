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

import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.core.vault.wipe

/**
 * The opt-in passkey backup of an identity (plan section 3): a copy of the user key that a passkey can
 * open, kept in Block Store, so that a new phone with the same synced passkey can restore the identity.
 *
 * It is an extra copy, never the only one: [enable] refuses an identity whose own backup is not checked.
 * Whoever controls the user's Google account and its screen lock could restore the identity with it, which
 * the app says before turning it on.
 */
class PasskeyBackup(
    private val vault: IdentityVault,
    private val passkeys: PasskeyPrf,
    private val store: BackupStore,
) {
    /** A backup found in the store, to offer on a new phone. */
    class Found(val userId: String, internal val sealed: PasskeyBackupFormat.Sealed)

    /** Whether [userId] has a passkey backup in the store. */
    suspend fun isEnabled(userId: String): Boolean = store.get(keyOf(userId)) != null

    /**
     * Makes the backup: creates the passkey, opens the user key (the user confirms with [prompt]), seals it
     * with the passkey's PRF output, and stores it.
     *
     * @throws IllegalStateException if the identity's own backup is not checked
     * @throws PrfUnsupportedException if the passkey provider can't do PRF
     */
    suspend fun enable(userId: String, displayName: String?, prompt: AuthPrompt) {
        val info = vault.identities.value.firstOrNull { it.userId == userId }
            ?: throw IllegalArgumentException("No such identity on this phone")
        check(info.backupVerified) { "Check your own backup of the key first" }

        val created = passkeys.create(
            userId = Id.of(userId).bytes,
            userName = "Boson " + userId.take(8),
            displayName = displayName ?: "Boson Identity",
            salt = PasskeyBackupFormat.PRF_SALT,
        )
        try {
            val sealed = vault.withUserKey(userId, prompt) { key ->
                val bytes = key.privateKey().bytes()
                try {
                    PasskeyBackupFormat.seal(created.prf, created.credentialId, userId, bytes)
                } finally {
                    bytes.wipe()
                }
            }
            store.put(keyOf(userId), PasskeyBackupFormat.encode(sealed))
        } finally {
            created.prf.wipe()
        }
    }

    /** Deletes the backup from the store. The passkey stays in the user's password manager until they remove it. */
    suspend fun disable(userId: String) = store.delete(keyOf(userId))

    /** The backups in the store, for a new phone to offer. Unreadable entries are skipped. */
    suspend fun found(): List<Found> = store.all().entries.mapNotNull { (key, bytes) ->
        if (!key.startsWith(KEY_PREFIX)) return@mapNotNull null
        runCatching { PasskeyBackupFormat.decode(bytes) }.getOrNull()?.let { Found(it.userId, it) }
    }

    /**
     * Opens [found] with its passkey: the user picks it in the provider and confirms. Returns the user key,
     * checked to be the identity's.
     *
     * @throws javax.crypto.AEADBadTagException if the passkey is not the backup's
     */
    suspend fun open(found: Found): Signature.KeyPair {
        val result = passkeys.evaluate(found.sealed.credentialId, PasskeyBackupFormat.PRF_SALT)
        val bytes = try {
            PasskeyBackupFormat.open(result.prf, found.sealed)
        } finally {
            result.prf.wipe()
        }
        try {
            val keyPair = Signature.KeyPair.fromPrivateKey(bytes)
            check(Id.of(keyPair.publicKey().bytes()).toString() == found.userId) { "The backup holds another identity's key" }
            return keyPair
        } finally {
            bytes.wipe()
        }
    }

    private fun keyOf(userId: String) = KEY_PREFIX + userId

    private companion object {
        const val KEY_PREFIX = "boson.identity.backup."
    }
}
