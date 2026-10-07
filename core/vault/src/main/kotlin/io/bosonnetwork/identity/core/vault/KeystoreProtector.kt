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

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import io.bosonnetwork.identity.core.model.AppError
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The [KeyProtector] backed by the Android Keystore: an AES-256-GCM key per secret, in StrongBox
 * when the phone has one, usable only while the phone is unlocked.
 *
 * - [Protection.PER_USE] keys need the user to authenticate for every single operation, through a
 *   prompt bound to the cipher.
 * - [Protection.SESSION] keys are usable for [SESSION_SECONDS] after any successful authentication.
 *
 * Adding a fingerprint does not invalidate the keys: that would destroy the identity on the phone
 * for no security gain, since the screen lock is accepted anyway.
 */
class KeystoreProtector(
    private val context: Context,
    private val authenticator: Authenticator,
) : KeyProtector {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun isAvailable(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override suspend fun seal(alias: String, protection: Protection, plaintext: ByteArray, prompt: AuthPrompt): Sealed {
        val key = existingKey(alias) ?: createKey(alias, protection)
        val cipher = ready(protection, prompt) {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        }
        return Sealed(cipher.iv, cipher.doFinal(plaintext))
    }

    override suspend fun open(alias: String, protection: Protection, sealed: Sealed, prompt: AuthPrompt): ByteArray {
        val key = existingKey(alias) ?: throw AppError.KeyUnavailable("The key protecting this secret is gone")
        val cipher = ready(protection, prompt) {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
            }
        }
        return cipher.doFinal(sealed.data)
    }

    override fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    // Initializes the cipher and gets it authorized as the key's protection requires.
    private suspend fun ready(protection: Protection, prompt: AuthPrompt, init: () -> Cipher): Cipher {
        try {
            return when (protection) {
                Protection.PER_USE -> authenticator.authorize(init(), prompt)
                Protection.SESSION -> try {
                    init()
                } catch (e: UserNotAuthenticatedException) {
                    unlockSession(prompt, init)
                }
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw AppError.KeyUnavailable("The key protecting this secret was invalidated", e)
        }
    }

    // One session prompt at a time. Screens open side by side ask for their keys together; without
    // this each would show its own prompt, and a second prompt cancels the first. Whoever waited
    // tries the key again first, since the prompt before it most likely unlocked the session; and
    // whoever waited behind a dismissed prompt is dismissed too, rather than asked again at once.
    private val sessionPrompt = Mutex()
    private var prompts = 0L
    private var lastDismissed = false

    private suspend fun unlockSession(prompt: AuthPrompt, init: () -> Cipher): Cipher {
        val seen = prompts
        return sessionPrompt.withLock {
            try {
                init()
            } catch (e: UserNotAuthenticatedException) {
                if (prompts != seen && lastDismissed) throw AppError.AuthenticationCancelled("The prompt was dismissed")
                prompts++
                try {
                    authenticator.authenticate(prompt)
                    lastDismissed = false
                } catch (cancelled: AppError.AuthenticationCancelled) {
                    lastDismissed = true
                    throw cancelled
                }
                init()
            }
        }
    }

    private fun existingKey(alias: String): SecretKey? = keyStore.getKey(alias, null) as SecretKey?

    private fun createKey(alias: String, protection: Protection): SecretKey {
        if (!isAvailable()) throw AppError.KeyUnavailable("Set a screen lock to protect your identity")
        val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return try {
            generate(alias, protection, strongBox)
        } catch (e: StrongBoxUnavailableException) {
            generate(alias, protection, strongBox = false)
        }
    }

    private fun generate(alias: String, protection: Protection, strongBox: Boolean): SecretKey {
        val timeout = if (protection == Protection.PER_USE) 0 else SESSION_SECONDS
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                timeout,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            .setInvalidatedByBiometricEnrollment(false)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    companion object {
        /** How long a [Protection.SESSION] key stays usable after the user authenticated. */
        const val SESSION_SECONDS = 300

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
