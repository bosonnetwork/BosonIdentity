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

import io.bosonnetwork.Id
import io.bosonnetwork.crypto.HybridTrustManager
import io.bosonnetwork.identity.core.model.NodeAddress
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The logos nodes announce in their status, fetched once per run and kept in memory. A logo is a
 * nicety: any failure (no logo, too large, an unreadable format, an untrusted certificate) gives null,
 * and the node is shown by its identicon instead.
 */
class NodeLogos {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, ByteArray?>()

    /** The image bytes of [logo], a URL or a path at [node]; null when there is none to show. */
    suspend fun load(node: NodeAddress, logo: String?): ByteArray? {
        val url = resolve(node, logo) ?: return null
        mutex.withLock { if (cache.containsKey(url)) return cache[url] }
        val bytes = withContext(Dispatchers.IO) { runCatching { fetch(node, URL(url)) }.getOrNull() }
        mutex.withLock { cache[url] = bytes }
        return bytes
    }

    private fun fetch(node: NodeAddress, url: URL): ByteArray? {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            // The node's own host may have a self-signed certificate pinned to its id, as the Director
            // client accepts it; anywhere else, only the usual CA-issued certificates.
            val nodeId = node.nodeId
            if (connection is HttpsURLConnection && nodeId != null && url.host == URI(node.url).host) {
                val tls = SSLContext.getInstance("TLS")
                tls.init(null, arrayOf(HybridTrustManager(nodeId, Id.of(nodeId).bytes)), null)
                connection.sslSocketFactory = tls.socketFactory
                // A CA-issued chain is checked against the host name by the trust manager itself; a
                // self-signed one names the node id, not the host.
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            if (connection.responseCode != 200) return null
            if (connection.contentType?.startsWith("image/") != true) return null
            if (connection.contentLengthLong > MAX_BYTES) return null
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
            }
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 10_000
        private const val MAX_BYTES = 512 * 1024

        /** [logo] as an absolute http(s) URL: a path is taken as one at [node]. Null when it isn't usable. */
        fun resolve(node: NodeAddress, logo: String?): String? {
            val text = logo?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching {
                val resolved = URI(node.url + "/").resolve(text)
                resolved.toString().takeIf { resolved.scheme == "https" || resolved.scheme == "http" }
            }.getOrNull()
        }
    }
}
