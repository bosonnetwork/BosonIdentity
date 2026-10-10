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

package io.bosonnetwork.identity.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.Id
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.AvatarStore
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.call
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import java.net.URI
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

/** Removing the identity from this phone, once the user confirmed it by its first characters. */
data class Removal(
    /** Signing this phone out of the node needs the account passphrase. */
    val needsPassphrase: Boolean = false,
    /** The node couldn't be reached to sign this phone out; the user may remove it anyway. */
    val unreachable: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

data class SettingsUiState(
    val identity: IdentityInfo? = null,
    /** Set while a removal is under way and needs more from the user. */
    val removal: Removal? = null,
    /** Why removing the identity from this phone failed, if it did. */
    val removeError: String? = null,
    /** Set when the last identity was removed from this phone: onboarding starts again. */
    val noIdentityLeft: Boolean = false,
)

/**
 * The Settings tab's part that concerns the identity in use on this phone: what this phone is to it,
 * and removing it from this phone. The app's own settings (theme, language) belong to the app and
 * reach the screen from there.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val vault: IdentityVault,
    private val activeIdentity: ActiveIdentity,
    private val nodes: NodeConnections,
    private val avatars: AvatarStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            activeIdentity.active.collect { identity ->
                val switched = identity?.userId != _state.value.identity?.userId
                _state.update {
                    if (switched) it.copy(identity = identity, removal = null, removeError = null) else it.copy(identity = identity)
                }
            }
        }
    }

    /**
     * Removes the identity from this phone, once the user typed [typed] matching [removalCode] and
     * confirmed with their screen lock. This phone is first signed out of the home node (its device
     * removed there), so no forgotten device stays registered; that may need the account [passphrase].
     * If the node can't be reached, the user may remove it anyway ([force]). Its keys are then deleted
     * from this phone; the account at the node stays.
     */
    fun remove(typed: String, passphrase: String? = null, force: Boolean = false) {
        val identity = _state.value.identity ?: return
        if (typed.trim() != removalCode(identity.userId)) return
        _state.update { it.copy(removal = (it.removal ?: Removal()).copy(busy = true, error = null), removeError = null) }
        viewModelScope.launch {
            try {
                val prompt = AuthPrompt(
                    context.getString(R.string.set_remove_prompt_title),
                    context.getString(R.string.set_remove_prompt_subtitle),
                )
                val node = identity.homeNode
                val deviceId = identity.deviceId
                if (!force) {
                    try {
                        if (node != null && deviceId != null) {
                            nodes.asUser(identity.userId, node, prompt) { client, _ ->
                                try {
                                    call { client.removeDevice(Id.of(deviceId), passphrase).await() }
                                } catch (e: AppError.NotFound) {
                                    // Already gone from the node.
                                }
                            }
                        } else {
                            vault.withUserKey(identity.userId, prompt) { }
                        }
                    } catch (e: AppError.KeyUnavailable) {
                        // The key can no longer be unlocked: it can't sign anything out, and keeping it helps no one.
                    }
                }
                nodes.forget(identity.userId)
                vault.remove(identity.userId)
                avatars.put(identity.userId, null)
                _state.update { it.copy(removal = null) }
                if (vault.identities.value.isEmpty()) _state.update { it.copy(noIdentityLeft = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.AuthenticationCancelled) {
                _state.update { it.copy(removal = null) }
            } catch (e: AppError.PassphraseRequired) {
                _state.update { it.copy(removal = Removal(needsPassphrase = true)) }
            } catch (e: AppError.Forbidden) {
                val message = if (passphrase != null) context.getString(R.string.set_wrong_passphrase) else e.userMessage(context)
                _state.update { it.copy(removal = Removal(needsPassphrase = passphrase != null, error = message)) }
            } catch (e: AppError.RateLimited) {
                _state.update { it.copy(removal = Removal(needsPassphrase = true, error = e.userMessage(context))) }
            } catch (e: AppError) {
                // Most likely the node is out of reach: offer to remove the identity from this phone anyway.
                _state.update { it.copy(removal = Removal(unreachable = e.userMessage(context))) }
            } catch (e: Exception) {
                _state.update { it.copy(removal = null, removeError = e.userMessage(context)) }
            }
        }
    }

    /** The user gave up removing the identity. */
    fun cancelRemoval() = _state.update { it.copy(removal = null) }
}

/** What the user types to confirm removing an identity from this phone: the first four characters of its id. */
fun removalCode(userId: String): String = userId.take(4)

/** A node's host name, to name it in a sentence; its URL when that has none. */
internal fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url
