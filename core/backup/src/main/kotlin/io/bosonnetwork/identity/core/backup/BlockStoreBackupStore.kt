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

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import kotlinx.coroutines.tasks.await

/** [BackupStore] in Android Block Store, backed up to the user's Google account end to end encrypted. */
class BlockStoreBackupStore(context: Context) : BackupStore {
    private val client = Blockstore.getClient(context)

    override suspend fun isCloudBackupAvailable(): Boolean = client.isEndToEndEncryptionAvailable.await()

    override suspend fun put(key: String, bytes: ByteArray) {
        client.storeBytes(
            StoreBytesData.Builder().setKey(key).setBytes(bytes).setShouldBackupToCloud(true).build(),
        ).await()
    }

    override suspend fun get(key: String): ByteArray? =
        client.retrieveBytes(RetrieveBytesRequest.Builder().setKeys(listOf(key)).build()).await()
            .blockstoreDataMap[key]?.bytes

    override suspend fun all(): Map<String, ByteArray> =
        client.retrieveBytes(RetrieveBytesRequest.Builder().setRetrieveAll(true).build()).await()
            .blockstoreDataMap.mapValues { it.value.bytes }

    override suspend fun delete(key: String) {
        client.deleteBytes(DeleteBytesRequest.Builder().setKeys(listOf(key)).build()).await()
    }
}
