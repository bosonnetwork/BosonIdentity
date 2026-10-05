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

import android.app.Activity
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import io.bosonnetwork.identity.core.model.AppError
import java.lang.ref.WeakReference
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * [PasskeyPrf] through Android Credential Manager. The passkey is for [PasskeyBackupFormat.RP_ID], which
 * the site's assetlinks.json delegates to this app (`get_login_creds`). Every ceremony requires user
 * verification: CTAP's hmac-secret keeps different secrets with and without it, so a ceremony without it
 * would give another PRF output and never open the backup.
 */
class CredentialManagerPrf : PasskeyPrf {
    private var activity = WeakReference<Activity>(null)

    /** Makes [activity] the one the provider's sheets are shown over. */
    fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
    }

    // Passkeys through Credential Manager are on Android 9 and later; this app needs Android 11 anyway.
    override fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    override suspend fun create(userId: ByteArray, userName: String, displayName: String, salt: ByteArray): PrfResult {
        val host = activity.get() ?: throw IllegalStateException("No activity to show the passkey sheet over")
        val request = JSONObject()
            .put("challenge", b64(randomBytes()))
            .put("rp", JSONObject().put("id", PasskeyBackupFormat.RP_ID).put("name", "Boson Identity"))
            .put("user", JSONObject().put("id", b64(userId)).put("name", userName).put("displayName", displayName))
            .put("pubKeyCredParams", JSONArray()
                .put(JSONObject().put("type", "public-key").put("alg", -7))
                .put(JSONObject().put("type", "public-key").put("alg", -8))
                .put(JSONObject().put("type", "public-key").put("alg", -257)))
            .put("authenticatorSelection", JSONObject().put("residentKey", "required").put("userVerification", "required"))
            .put("extensions", prfExtension(salt))
        val response = try {
            CredentialManager.create(host).createCredential(host, CreatePublicKeyCredentialRequest(request.toString()))
                as CreatePublicKeyCredentialResponse
        } catch (e: CreateCredentialCancellationException) {
            throw AppError.AuthenticationCancelled(e.message, e)
        } catch (e: CreateCredentialException) {
            throw AppError.Unknown(e.errorMessage?.toString() ?: e.type, e)
        }
        val json = JSONObject(response.registrationResponseJson)
        val credentialId = unb64(json.getString("rawId"))
        // Some providers give the PRF output at creation already; the others, on the first assertion.
        prfOf(json)?.let { return PrfResult(credentialId, it) }
        return evaluate(credentialId, salt)
    }

    override suspend fun evaluate(credentialId: ByteArray?, salt: ByteArray): PrfResult {
        val host = activity.get() ?: throw IllegalStateException("No activity to show the passkey sheet over")
        val request = JSONObject()
            .put("challenge", b64(randomBytes()))
            .put("rpId", PasskeyBackupFormat.RP_ID)
            .put("userVerification", "required")
            .put("extensions", prfExtension(salt))
        if (credentialId != null) {
            request.put("allowCredentials", JSONArray().put(JSONObject().put("type", "public-key").put("id", b64(credentialId))))
        }
        val result = try {
            CredentialManager.create(host).getCredential(host, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(request.toString()))))
        } catch (e: GetCredentialCancellationException) {
            throw AppError.AuthenticationCancelled(e.message, e)
        } catch (e: NoCredentialException) {
            throw AppError.NotFound("No passkey for this backup on this phone", e)
        } catch (e: GetCredentialException) {
            throw AppError.Unknown(e.errorMessage?.toString() ?: e.type, e)
        }
        val credential = result.credential as? PublicKeyCredential
            ?: throw IllegalStateException("The provider answered with something other than a passkey")
        val json = JSONObject(credential.authenticationResponseJson)
        val prf = prfOf(json) ?: throw PrfUnsupportedException("This passkey provider can't unlock a backup (no PRF)")
        return PrfResult(unb64(json.getString("rawId")), prf)
    }

    private fun prfExtension(salt: ByteArray) =
        JSONObject().put("prf", JSONObject().put("eval", JSONObject().put("first", b64(salt))))

    // clientExtensionResults.prf.results.first, when the provider gave it.
    private fun prfOf(response: JSONObject): ByteArray? =
        response.optJSONObject("clientExtensionResults")
            ?.optJSONObject("prf")
            ?.optJSONObject("results")
            ?.optString("first")
            ?.takeIf { it.isNotEmpty() }
            ?.let(::unb64)

    private fun randomBytes() = ByteArray(32).also(SecureRandom()::nextBytes)

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}
