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

package io.bosonnetwork.identity.feature.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.AuthProvider
import io.bosonnetwork.director.client.Device
import io.bosonnetwork.director.client.LinkedIdentity
import io.bosonnetwork.director.client.Profile
import io.bosonnetwork.director.client.RecoveryMethods
import io.bosonnetwork.director.client.SecurityEvent
import io.bosonnetwork.director.client.exceptions.DirectorException
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import io.bosonnetwork.identity.core.vault.KeyProtector
import io.bosonnetwork.identity.core.vault.Protection
import io.bosonnetwork.identity.core.vault.Sealed
import io.bosonnetwork.identity.core.vault.VaultStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

/**
 * Stands in for the Keystore: seals nothing, so keys round-trip as they are. [failSeals] makes the next
 * seals fail as if the user dismissed the prompt.
 */
class FakeKeystore : KeyProtector {
    var failSeals = 0

    override fun isAvailable() = true

    override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt): Sealed {
        if (failSeals > 0) {
            failSeals--
            throw AppError.AuthenticationCancelled("dismissed")
        }
        return Sealed(ByteArray(12), plaintext.copyOf())
    }

    override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt) = sealed.data.copyOf()

    override fun delete(alias: String) = Unit
}

/** A real vault and a real choice of identity, in a temporary directory. */
class TestVault {
    val dir: File = Files.createTempDirectory("security").toFile()
    val keystore = FakeKeystore()
    val vault = IdentityVault(VaultStore(File(dir, "vault")), keystore)
    private val storeJob = Job()
    val active = ActiveIdentity(
        vault,
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob)) { File(dir, "active.preferences_pb") },
    )

    /** Adds an identity joined to [node], with this phone's device [deviceId]; returns its id. */
    suspend fun addIdentity(
        node: NodeAddress? = NODE,
        backupVerified: Boolean = true,
        deviceId: String = Id.random().toString(),
        userKey: Signature.KeyPair = Signature.KeyPair.random(),
    ): String {
        val userId = vault.add(userKey, null, backupVerified, keepUserKey = true, prompt = AuthPrompt("Confirm")).userId
        vault.update(userId) { it.copy(homeNode = node, deviceId = deviceId) }
        return userId
    }

    fun close() {
        storeJob.cancel()
        dir.deleteRecursively()
    }

    companion object {
        val NODE = NodeAddress("https://node.example", "nodeid")
    }
}

// The client's models, as Jackson would decode them: their constructors are package-private.

fun profile(passphrase: Boolean = false): Profile =
    Profile::class.java.getDeclaredConstructor(
        Id::class.java, Boolean::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
        Long::class.java, Long::class.java, String::class.java, Boolean::class.java,
    ).apply { isAccessible = true }.newInstance(Id.random(), false, "Alice", null, null, null, 0L, 0L, "Free", passphrase)

fun device(id: Id = Id.random(), name: String, kind: String = Device.KIND_APP, recovery: Boolean = false): Device =
    Device::class.java.getDeclaredConstructor(
        Id::class.java, Id::class.java, String::class.java, String::class.java, String::class.java,
        Boolean::class.java, Boolean::class.java, Long::class.java, Long::class.java, Long::class.java, String::class.java,
    ).apply { isAccessible = true }.newInstance(id, Id.random(), name, "Portal", kind, false, recovery, 1_000L, 1_000L, 0L, null)

fun recoveryMethods(oauth: Int = 0, passkeys: Int = 0, codes: Int = 0): RecoveryMethods =
    RecoveryMethods::class.java.getDeclaredConstructor(Int::class.java, Int::class.java, Int::class.java)
        .apply { isAccessible = true }.newInstance(oauth, passkeys, codes)

fun linked(sessionId: Id = Id.random(), provider: String? = "github", name: String? = "Alice", email: String? = "alice@example.com"): LinkedIdentity =
    LinkedIdentity::class.java.getDeclaredConstructor(
        Id::class.java, String::class.java, String::class.java, String::class.java, Boolean::class.java, String::class.java, Long::class.java,
    ).apply { isAccessible = true }.newInstance(sessionId, provider, name, email, true, null, 1_000L)

fun event(kind: String, method: String? = null, at: Long = 1_000L, address: String? = null): SecurityEvent =
    SecurityEvent::class.java.getDeclaredConstructor(String::class.java, String::class.java, Long::class.java, String::class.java)
        .apply { isAccessible = true }.newInstance(kind, method, at, address)

fun provider(id: String, name: String): AuthProvider =
    AuthProvider::class.java.getDeclaredConstructor(String::class.java, String::class.java)
        .apply { isAccessible = true }.newInstance(id, name)

/** A call the node refused with [status]. */
fun <T> refused(status: Int): CompletableFuture<T> =
    CompletableFuture.failedFuture(DirectorException.fromResponse(status, "refused", null))

fun <T> done(value: T): CompletableFuture<T> = CompletableFuture.completedFuture(value)

/** A call that answered nothing, and succeeded. */
fun ok(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
