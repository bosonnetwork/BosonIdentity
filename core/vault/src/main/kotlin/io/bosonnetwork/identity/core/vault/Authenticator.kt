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

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.bosonnetwork.identity.core.model.AppError
import java.lang.ref.WeakReference
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Asks the user to authenticate with the system prompt: a strong biometric or the screen lock. */
interface Authenticator {
    /**
     * Shows the prompt bound to [cipher], so the Keystore lets that one cipher operate after the
     * user authenticated, and no other code can get the authorization.
     *
     * @return the cipher, now usable
     * @throws AppError.AuthenticationCancelled if the user dismissed the prompt
     */
    suspend fun authorize(cipher: Cipher, prompt: AuthPrompt): Cipher

    /** Shows the prompt without a cipher, which unlocks [Protection.SESSION] keys for a while. */
    suspend fun authenticate(prompt: AuthPrompt)
}

/**
 * The [Authenticator] that shows the system BiometricPrompt over the activity in front. The app's
 * activity registers itself with [attach] when it is created; the reference is weak, so a destroyed
 * activity is never held.
 */
class ActivityAuthenticator : Authenticator {
    private var activity = WeakReference<FragmentActivity>(null)

    /** Makes [activity] the one the prompt is shown over. */
    fun attach(activity: FragmentActivity) {
        this.activity = WeakReference(activity)
    }

    override suspend fun authorize(cipher: Cipher, prompt: AuthPrompt): Cipher =
        show(prompt, BiometricPrompt.CryptoObject(cipher)).cryptoObject?.cipher
            ?: throw AppError.Unknown("The prompt returned no cipher")

    override suspend fun authenticate(prompt: AuthPrompt) {
        show(prompt, null)
    }

    private suspend fun show(prompt: AuthPrompt, crypto: BiometricPrompt.CryptoObject?): BiometricPrompt.AuthenticationResult =
        withContext(Dispatchers.Main) {
            val host = activity.get() ?: throw AppError.Unknown("No activity to show the prompt over")
            suspendCancellableCoroutine { continuation ->
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(result)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!continuation.isActive) return
                        val error = when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED,
                            -> AppError.AuthenticationCancelled(errString.toString())
                            else -> AppError.Unknown(errString.toString())
                        }
                        continuation.resumeWithException(error)
                    }
                    // onAuthenticationFailed (a finger not recognized) leaves the prompt up for another try.
                }
                val biometricPrompt = BiometricPrompt(host, ContextCompat.getMainExecutor(host), callback)
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(prompt.title)
                    .apply { prompt.subtitle?.let(::setSubtitle) }
                    // The screen lock is always accepted beside a strong biometric: a phone without
                    // a fingerprint must still be able to use its identity.
                    .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                    .setConfirmationRequired(true)
                    .build()
                if (crypto != null) biometricPrompt.authenticate(info, crypto) else biometricPrompt.authenticate(info)
                continuation.invokeOnCancellation { biometricPrompt.cancelAuthentication() }
            }
        }
}
