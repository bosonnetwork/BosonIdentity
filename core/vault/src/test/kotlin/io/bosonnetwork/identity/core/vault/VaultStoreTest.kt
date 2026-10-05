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
import io.bosonnetwork.identity.core.model.NodeAddress
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vault file's format: what it keeps, and how it reads files written by older versions. */
class VaultStoreTest {

    private val dir: File = Files.createTempDirectory("store").toFile()
    private val sealed = Sealed(ByteArray(12) { 1 }, ByteArray(48) { 2 })

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `whether an identity administers its node is kept`() {
        val info = IdentityInfo(
            userId = "user",
            name = "Root",
            backupVerified = true,
            homeNode = NodeAddress("https://node.example", "node"),
            deviceId = "device",
            administers = true,
        )
        VaultStore(dir).write(listOf(VaultEntry(info, sealed, sealed), VaultEntry(IdentityInfo("other"), null, sealed)))

        val read = VaultStore(dir).read()
        assertEquals(info, read[0].info)
        assertTrue(read[0].info.administers)
        assertFalse(read[1].info.administers)
    }

    @Test
    fun `a vault written before admin mode reads as administering nothing`() {
        File(dir, "vault.json").writeText(
            """
            {"version":1,"identities":[{"userId":"user","backupVerified":true,
              "homeNode":{"url":"https://node.example","nodeId":"node"},"deviceId":"device",
              "deviceKey":{"iv":"AAAAAAAAAAAAAAAA","data":"AQID"}}]}
            """.trimIndent(),
        )
        val info = VaultStore(dir).read().single().info
        assertEquals("user", info.userId)
        assertFalse(info.administers)
    }
}
