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

import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.model.LocalProfile
import io.bosonnetwork.identity.core.model.ProfileFields
import io.bosonnetwork.identity.core.model.NodeAddress
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * One identity as stored: what the app knows about it, and its keys sealed by the Keystore.
 *
 * @property userKey the sealed 32-byte seed of the user key, or null when the user chose not to keep
 *   it on this phone
 * @property deviceKey the sealed 32-byte seed of this app's device key under the identity
 */
class VaultEntry(
    val info: IdentityInfo,
    val userKey: Sealed?,
    val deviceKey: Sealed,
)

/**
 * The vault file, in the app's private storage. Every write replaces the file atomically: a new file
 * is written and synced, then renamed over the old one, and the directory is synced, so a power cut
 * leaves either the old vault or the new one, never a torn or empty file.
 */
class VaultStore(private val dir: File) {
    private val file = File(dir, FILE_NAME)

    fun read(): List<VaultEntry> {
        if (!file.exists()) return emptyList()
        val root = JSONObject(file.readText())
        val entries = root.getJSONArray("identities")
        return (0 until entries.length()).map { decode(entries.getJSONObject(it)) }
    }

    fun write(entries: List<VaultEntry>) {
        dir.mkdirs()
        val root = JSONObject()
            .put("version", VERSION)
            .put("identities", JSONArray().apply { entries.forEach { put(encode(it)) } })
        val tmp = File(dir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(root.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw java.io.IOException("Could not replace the vault file")
        syncDirectory()
    }

    // Makes the rename itself durable. Opening a directory works on Linux (and so Android); where it
    // does not, the rename is still atomic, only not yet flushed.
    private fun syncDirectory() {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            // Best effort.
        }
    }

    private fun encode(entry: VaultEntry): JSONObject {
        val info = entry.info
        return JSONObject()
            .put("userId", info.userId)
            .put("profile", encodeProfile(info.profile))
            .put("backupVerified", info.backupVerified)
            .putOpt("homeNode", info.homeNode?.let { JSONObject().put("url", it.url).putOpt("nodeId", it.nodeId) })
            .putOpt("deviceId", info.deviceId)
            .put("administers", info.administers)
            .putOpt("cardVersion", info.cardVersion)
            .putOpt("cardRevision", info.cardRevision)
            .putOpt("logReviewedAt", info.logReviewedAt)
            .putOpt("userKey", entry.userKey?.let(::encodeSealed))
            .put("deviceKey", encodeSealed(entry.deviceKey))
    }

    private fun encodeProfile(profile: LocalProfile): JSONObject =
        encodeFields(profile.fields)
            .putOpt("revision", profile.revision)
            .putOpt("synced", profile.synced?.let(::encodeFields))

    private fun encodeFields(fields: ProfileFields): JSONObject =
        JSONObject()
            .putOpt("name", fields.name)
            .putOpt("bio", fields.bio)
            .putOpt("email", fields.email)
            .putOpt("avatar", fields.avatar)

    private fun decodeProfile(json: JSONObject?, legacyName: String?): LocalProfile {
        // Vaults from before the local profile kept only the name, as the home node had it: never synced.
        if (json == null) return LocalProfile(ProfileFields(name = legacyName))
        return LocalProfile(
            fields = decodeFields(json),
            revision = if (json.has("revision") && !json.isNull("revision")) json.getLong("revision") else null,
            synced = json.optJSONObject("synced")?.let(::decodeFields),
        )
    }

    private fun decodeFields(json: JSONObject): ProfileFields =
        ProfileFields(
            name = json.optStringOrNull("name"),
            bio = json.optStringOrNull("bio"),
            email = json.optStringOrNull("email"),
            avatar = json.optStringOrNull("avatar"),
        )

    private fun decode(json: JSONObject): VaultEntry {
        val home = json.optJSONObject("homeNode")
        val info = IdentityInfo(
            userId = json.getString("userId"),
            profile = decodeProfile(json.optJSONObject("profile"), json.optStringOrNull("name")),
            backupVerified = json.optBoolean("backupVerified"),
            homeNode = home?.let { NodeAddress(it.getString("url"), it.optStringOrNull("nodeId")) },
            deviceId = json.optStringOrNull("deviceId"),
            // Absent from vaults written before admin mode existed.
            administers = json.optBoolean("administers", false),
            cardVersion = if (json.has("cardVersion") && !json.isNull("cardVersion")) json.getInt("cardVersion") else null,
            cardRevision = if (json.has("cardRevision") && !json.isNull("cardRevision")) json.getLong("cardRevision") else null,
            logReviewedAt = if (json.has("logReviewedAt") && !json.isNull("logReviewedAt")) json.getLong("logReviewedAt") else null,
        )
        return VaultEntry(
            info,
            json.optJSONObject("userKey")?.let(::decodeSealed),
            decodeSealed(json.getJSONObject("deviceKey")),
        )
    }

    private fun encodeSealed(sealed: Sealed): JSONObject =
        JSONObject().put("iv", base64.encodeToString(sealed.iv)).put("data", base64.encodeToString(sealed.data))

    private fun decodeSealed(json: JSONObject): Sealed =
        Sealed(unbase64.decode(json.getString("iv")), unbase64.decode(json.getString("data")))

    private fun JSONObject.optStringOrNull(name: String): String? = if (!has(name) || isNull(name)) null else optString(name)

    private companion object {
        const val FILE_NAME = "vault.json"
        const val VERSION = 1
        val base64: Base64.Encoder = Base64.getEncoder()
        val unbase64: Base64.Decoder = Base64.getDecoder()
    }
}
