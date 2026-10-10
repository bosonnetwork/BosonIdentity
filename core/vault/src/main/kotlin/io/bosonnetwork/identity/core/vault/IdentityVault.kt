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

import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.model.LocalProfile
import io.bosonnetwork.identity.core.model.ProfileFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The identities on this phone and their keys.
 *
 * Each identity has two keys, stored only as 32-byte seeds sealed by the Keystore:
 * - the **user key**, the identity itself, sealed under a [Protection.PER_USE] key, so the user
 *   confirms every use of it;
 * - this app's **device key** under the identity, sealed under a [Protection.SESSION] key, for the
 *   app's routine calls.
 *
 * A key is opened only for the moment it is needed: its seed is wiped right after, and the user key
 * pair is destroyed (its own copy of the seed wiped) when the block using it returns.
 */
class IdentityVault(
    private val store: VaultStore,
    private val protector: KeyProtector,
) {
    private val mutex = Mutex()
    private val entries = MutableStateFlow<List<VaultEntry>>(emptyList())
    private val _identities = MutableStateFlow<List<IdentityInfo>>(emptyList())
    private var loaded = false

    /** The identities on this phone, in the order they were added. */
    val identities: StateFlow<List<IdentityInfo>> = _identities.asStateFlow()

    /** Whether the phone can protect keys at all (it needs a secure lock screen). */
    fun canProtectKeys(): Boolean = protector.isAvailable()

    /** Loads the vault file; called once at start, and implied by every other call. */
    suspend fun load() = mutex.withLock { ensureLoaded() }

    /**
     * Adds an identity: seals [userKey] (unless [keepUserKey] is false) and its device key - [deviceKey]
     * when the phone already registered one (moving from an old phone), or a new one. The user
     * authenticates once for the user key, and the device key usually rides on that.
     *
     * @throws AppError.Conflict if the identity is already on this phone
     */
    suspend fun add(
        userKey: Signature.KeyPair,
        name: String?,
        backupVerified: Boolean,
        keepUserKey: Boolean,
        prompt: AuthPrompt,
        deviceKey: Signature.KeyPair? = null,
    ): IdentityInfo = mutex.withLock {
        ensureLoaded()
        val userId = Id.of(userKey.publicKey().bytes()).toString()
        if (entries.value.any { it.info.userId == userId }) throw AppError.Conflict("This identity is already here")

        val userSeed = userKey.privateKey().seed()
        val deviceSeed = (deviceKey ?: Signature.KeyPair.random()).privateKey().seed()
        try {
            val sealedUser = if (keepUserKey) protector.seal(userAlias(userId), Protection.PER_USE, userSeed, prompt) else null
            val sealedDevice = protector.seal(deviceAlias(userId), Protection.SESSION, deviceSeed, prompt)
            val info = IdentityInfo(userId = userId, profile = LocalProfile(ProfileFields(name = name)), backupVerified = backupVerified)
            save(entries.value + VaultEntry(info, sealedUser, sealedDevice))
            info
        } catch (e: Exception) {
            protector.delete(userAlias(userId))
            protector.delete(deviceAlias(userId))
            throw e
        } finally {
            userSeed.wipe()
            deviceSeed.wipe()
        }
    }

    /** Whether the user key of [userId] is kept on this phone. */
    suspend fun keepsUserKey(userId: String): Boolean = mutex.withLock { ensureLoaded(); entry(userId).userKey != null }

    /**
     * Runs [block] with the user key of [userId], after the user confirmed with [prompt]. The key pair
     * is destroyed when the block returns, so [block] must finish everything it needs the key for.
     *
     * @throws AppError.KeyUnavailable if the user key is not kept on this phone, or its Keystore key is gone
     */
    suspend fun <T> withUserKey(userId: String, prompt: AuthPrompt, block: suspend (Signature.KeyPair) -> T): T {
        val sealed = mutex.withLock { ensureLoaded(); entry(userId).userKey }
            ?: throw AppError.KeyUnavailable("This identity's key is not kept on this phone")
        val seed = protector.open(userAlias(userId), Protection.PER_USE, sealed, prompt)
        val keyPair = try {
            Signature.KeyPair.fromSeed(seed)
        } finally {
            seed.wipe()
        }
        try {
            check(Id.of(keyPair.publicKey().bytes()).toString() == userId) { "The vault holds another identity's key" }
            return block(keyPair)
        } finally {
            keyPair.destroy()
        }
    }

    /**
     * This app's device key under [userId]. Opening it may show the prompt, when the phone was not
     * unlocked recently.
     */
    suspend fun deviceKey(userId: String, prompt: AuthPrompt): Signature.KeyPair {
        val sealed = mutex.withLock { ensureLoaded(); entry(userId).deviceKey }
        val seed = protector.open(deviceAlias(userId), Protection.SESSION, sealed, prompt)
        try {
            return Signature.KeyPair.fromSeed(seed)
        } finally {
            seed.wipe()
        }
    }

    /**
     * Replaces this app's device key under [userId] with [deviceKey], once the node knows the new one
     * (renewing the phone's device key). The old seal is overwritten.
     */
    suspend fun replaceDeviceKey(userId: String, deviceKey: Signature.KeyPair, prompt: AuthPrompt) {
        val seed = deviceKey.privateKey().seed()
        try {
            val sealed = protector.seal(deviceAlias(userId), Protection.SESSION, seed, prompt)
            mutex.withLock {
                ensureLoaded()
                val current = entry(userId)
                save(entries.value.map { if (it.info.userId == userId) VaultEntry(current.info, current.userKey, sealed) else it })
            }
        } finally {
            seed.wipe()
        }
    }

    /** Changes what the app knows about [userId]; the keys stay as they are. */
    suspend fun update(userId: String, transform: (IdentityInfo) -> IdentityInfo): IdentityInfo = mutex.withLock {
        ensureLoaded()
        val current = entry(userId)
        val info = transform(current.info)
        require(info.userId == userId) { "An identity's id can't change" }
        save(entries.value.map { if (it.info.userId == userId) VaultEntry(info, it.userKey, it.deviceKey) else it })
        info
    }

    /** Removes [userId] from this phone: its entry and the Keystore keys that sealed its keys. */
    suspend fun remove(userId: String) = mutex.withLock {
        ensureLoaded()
        save(entries.value.filterNot { it.info.userId == userId })
        protector.delete(userAlias(userId))
        protector.delete(deviceAlias(userId))
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val read = withContext(Dispatchers.IO) { store.read() }
        entries.value = read
        _identities.value = read.map { it.info }
        loaded = true
    }

    private suspend fun save(updated: List<VaultEntry>) {
        withContext(Dispatchers.IO) { store.write(updated) }
        entries.value = updated
        _identities.value = updated.map { it.info }
    }

    private fun entry(userId: String): VaultEntry =
        entries.value.firstOrNull { it.info.userId == userId }
            ?: throw AppError.NotFound("No such identity on this phone")

    private companion object {
        fun userAlias(userId: String) = "boson.identity.user.$userId"
        fun deviceAlias(userId: String) = "boson.identity.device.$userId"
    }
}
