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
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The avatars of the identities on this phone, kept in [dir], in the app's own files: an avatar is part
 * of the identity's profile, which lives on this phone first, so it is data, not a cache. Every place
 * that shows an identity reads [avatars], so the top bar, the switcher and the profile show the same
 * picture. One synced from the home node is kept with the validators it was downloaded with, so the node
 * can say it is still current without sending the image again. The profile names an avatar by its
 * [digest].
 */
class AvatarStore(private val dir: File) {
    private val mutex = Mutex()
    private var loaded = false
    private val held = mutableMapOf<String, Avatar>()
    private val _avatars = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    /** The images by user id; an identity without one is absent. */
    val avatars: StateFlow<Map<String, ByteArray>> = _avatars.asStateFlow()

    /** Reads what is kept on disk, once. */
    suspend fun load() {
        withContext(Dispatchers.IO) { mutex.withLock { ensureLoaded() } }
    }

    /** The avatar kept for [userId], with its validators; null when there is none. */
    suspend fun held(userId: String): Avatar? =
        withContext(Dispatchers.IO) { mutex.withLock { ensureLoaded(); held[userId] } }

    /** Keeps [avatar] as [userId]'s, or forgets it when null (the account has none, or the identity left). */
    suspend fun put(userId: String, avatar: Avatar?) {
        require(SAFE_ID.matches(userId)) { "Not a user id" }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoaded()
                val image = File(dir, "$userId$IMAGE")
                val meta = File(dir, "$userId$META")
                if (avatar == null) {
                    held -= userId
                    image.delete()
                    meta.delete()
                } else {
                    held[userId] = avatar
                    runCatching {
                        dir.mkdirs()
                        image.writeBytes(avatar.data)
                        Properties().apply {
                            setProperty(KEY_TYPE, avatar.contentType)
                            avatar.eTag.orElse(null)?.let { setProperty(KEY_ETAG, it) }
                            avatar.lastModified.orElse(null)?.let { setProperty(KEY_MODIFIED, it) }
                        }.let { props -> meta.outputStream().use { props.store(it, null) } }
                    }
                }
                _avatars.value = held.mapValues { it.value.data }
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        dir.listFiles { file -> file.name.endsWith(META) }?.forEach { meta ->
            val userId = meta.name.removeSuffix(META)
            if (!SAFE_ID.matches(userId)) return@forEach
            runCatching {
                val props = Properties().apply { meta.inputStream().use { load(it) } }
                val data = File(dir, "$userId$IMAGE").readBytes()
                held[userId] = Avatar.of(
                    props.getProperty(KEY_TYPE) ?: "application/octet-stream",
                    data,
                    props.getProperty(KEY_ETAG),
                    props.getProperty(KEY_MODIFIED),
                )
            }
        }
        _avatars.value = held.mapValues { it.value.data }
    }

    companion object {
        /** The SHA-256 of an avatar image, in hex: how the profile names it. */
        fun digest(image: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }

        private const val IMAGE = ".img"
        private const val META = ".properties"
        private const val KEY_TYPE = "type"
        private const val KEY_ETAG = "etag"
        private const val KEY_MODIFIED = "modified"

        // Base58: the only names written here, so a file name can't reach outside the directory.
        private val SAFE_ID = Regex("[1-9A-HJ-NP-Za-km-z]{32,64}")
    }
}
