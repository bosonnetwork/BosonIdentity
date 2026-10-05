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

package io.bosonnetwork.identity.feature.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.Id
import io.bosonnetwork.director.client.ProfileUpdate
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.AdminAccess
import io.bosonnetwork.identity.core.boson.AdminSession
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.call
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.vault.AuthPrompt
import io.bosonnetwork.identity.core.vault.IdentityVault
import java.net.URI
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The account as the home node has it. */
data class ProfileView(
    val name: String?,
    val bio: String?,
    val email: String?,
    val plan: String,
    val passphraseProtected: Boolean,
)

sealed interface ProfileState {
    /** No home node yet, so no account. */
    data object None : ProfileState
    data object Loading : ProfileState

    /** The user dismissed the unlock prompt. */
    data object Locked : ProfileState
    data class Ready(val view: ProfileView) : ProfileState
    data class Failed(val message: String) : ProfileState
}

/** The profile being edited, as typed. */
data class ProfileEdit(
    val name: String,
    val bio: String,
    val email: String,
    val passphrase: String = "",
    /** Whether saving needs the account passphrase: the account has one, or the node asked for it. */
    val needsPassphrase: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Opening admin mode for the home node, from the "Node administration" row. */
data class AdminStart(
    val busy: Boolean = false,
    /** Why it didn't open: most often, the identity doesn't administer the node. */
    val error: String? = null,
)

/** Removing the identity from this phone, once the user confirmed it by its first characters. */
data class Removal(
    /** Signing this phone out of the node needs the account passphrase. */
    val needsPassphrase: Boolean = false,
    /** The node couldn't be reached to sign this phone out; the user may remove it anyway. */
    val unreachable: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

data class AccountUiState(
    val identity: IdentityInfo? = null,
    val profile: ProfileState = ProfileState.None,
    /** Set while the edit dialog is open. */
    val edit: ProfileEdit? = null,
    /** Why removing the identity from this phone failed, if it did. */
    val removeError: String? = null,
    /** Set while a removal is under way and needs more from the user. */
    val removal: Removal? = null,
    /** Set when the last identity was removed from this phone: onboarding starts again. */
    val noIdentityLeft: Boolean = false,
    val admin: AdminStart = AdminStart(),
    /** Set once admin mode opened, so the app shows the Admin tab; cleared when that happened. */
    val adminOpened: Boolean = false,
)

/**
 * The account of the identity in use at its home node: its profile, the node, and the plan; and
 * removing the identity from this phone. Reading goes through this app's device key; changing the
 * profile is an act of authority, confirmed with the user key. The node's operators open admin mode
 * from here too.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val vault: IdentityVault,
    private val activeIdentity: ActiveIdentity,
    private val nodes: NodeConnections,
    private val adminSession: AdminSession,
    private val adminAccess: AdminAccess,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    private var loading: Job? = null
    private var saving: Job? = null

    init {
        viewModelScope.launch {
            // The identity picked in the switcher. A change of its name alone doesn't ask the node again.
            activeIdentity.active.collect { identity ->
                val previous = _state.value.identity
                val switched = identity?.userId != previous?.userId
                if (switched) saving?.cancel()
                _state.update {
                    it.copy(
                        identity = identity,
                        edit = if (switched) null else it.edit,
                        removeError = if (switched) null else it.removeError,
                        admin = if (switched) AdminStart() else it.admin,
                    )
                }
                if (switched || identity?.homeNode != previous?.homeNode) refresh()
            }
        }
    }

    /** Asks the home node again; after a dismissed prompt, this is the "Unlock" button. */
    fun refresh() {
        val identity = _state.value.identity
        // An answer still on its way may be for the identity shown before.
        loading?.cancel()
        if (identity?.homeNode == null) {
            _state.update { it.copy(profile = ProfileState.None) }
            return
        }
        _state.update { it.copy(profile = ProfileState.Loading) }
        loading = viewModelScope.launch {
            _state.update { it.copy(profile = load(identity)) }
        }
    }

    private suspend fun load(identity: IdentityInfo): ProfileState =
        try {
            val prompt = AuthPrompt(
                context.getString(R.string.acc_unlock_prompt_title),
                context.getString(R.string.acc_unlock_prompt_subtitle),
            )
            val client = nodes.deviceClient(identity, prompt)
            val profile = call { client.profile.await() }
            val view = ProfileView(
                name = profile.name.orElse(null)?.takeIf { it.isNotBlank() },
                bio = profile.bio.orElse(null)?.takeIf { it.isNotBlank() },
                email = profile.email.orElse(null)?.takeIf { it.isNotBlank() },
                plan = profile.planName,
                passphraseProtected = profile.isPassphraseProtected,
            )
            rememberName(identity, view.name)
            // An account marked as an administrator gets the Admin tab without opening admin mode first.
            adminAccess.reportProfile(identity.userId, profile.isAdmin)
            ProfileState.Ready(view)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.AuthenticationCancelled) {
            ProfileState.Locked
        } catch (e: Exception) {
            ProfileState.Failed(e.userMessage(context))
        }

    fun startEdit() {
        val view = (_state.value.profile as? ProfileState.Ready)?.view ?: return
        _state.update {
            it.copy(edit = ProfileEdit(view.name.orEmpty(), view.bio.orEmpty(), view.email.orEmpty(), needsPassphrase = view.passphraseProtected))
        }
    }

    fun onNameChange(value: String) = updateEdit { it.copy(name = value, error = null) }
    fun onBioChange(value: String) = updateEdit { it.copy(bio = value, error = null) }
    fun onEmailChange(value: String) = updateEdit { it.copy(email = value, error = null) }
    fun onPassphraseChange(value: String) = updateEdit { it.copy(passphrase = value, error = null) }

    fun cancelEdit() {
        saving?.cancel()
        _state.update { it.copy(edit = null) }
    }

    /**
     * Saves the changed fields, after the user confirms with their screen lock. When the node wants
     * the account passphrase, the dialog asks for it, and saving again sends it.
     */
    fun save() {
        val s = _state.value
        val identity = s.identity ?: return
        val node = identity.homeNode ?: return
        val view = (s.profile as? ProfileState.Ready)?.view ?: return
        val edit = s.edit ?: return
        if (edit.busy) return

        val name = edit.name.trim().ifEmpty { null }
        val bio = edit.bio.trim().ifEmpty { null }
        val email = edit.email.trim().ifEmpty { null }
        if (email != null && !looksLikeEmail(email)) {
            updateEdit { it.copy(error = context.getString(R.string.acc_edit_invalid_email)) }
            return
        }
        val update = ProfileUpdate()
        if (name != view.name) update.name(name)
        if (bio != view.bio) update.bio(bio)
        if (email != view.email) update.email(email)
        if (update.isEmpty) {
            _state.update { it.copy(edit = null) }
            return
        }
        val passphrase = edit.passphrase.ifEmpty { null }
        if (edit.needsPassphrase && passphrase == null) return

        updateEdit { it.copy(busy = true, error = null) }
        saving = viewModelScope.launch {
            try {
                val prompt = AuthPrompt(
                    context.getString(R.string.acc_edit_prompt_title),
                    context.getString(R.string.acc_edit_prompt_subtitle, node.host()),
                )
                nodes.asUser(identity.userId, node, prompt) { client, _ ->
                    call { client.updateProfile(update, passphrase).await() }
                }
                rememberName(identity, name)
                _state.update {
                    it.copy(profile = ProfileState.Ready(view.copy(name = name, bio = bio, email = email)), edit = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.PassphraseRequired) {
                updateEdit { it.copy(busy = false, needsPassphrase = true, passphrase = "", error = e.userMessage(context)) }
            } catch (e: AppError.Forbidden) {
                // With a passphrase given, the node refuses only a wrong one.
                val message = if (passphrase != null) context.getString(R.string.acc_wrong_passphrase) else e.userMessage(context)
                updateEdit { it.copy(busy = false, passphrase = "", error = message) }
            } catch (e: Exception) {
                updateEdit { it.copy(busy = false, error = e.userMessage(context)) }
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
                    context.getString(R.string.acc_remove_prompt_title),
                    context.getString(R.string.acc_remove_prompt_subtitle),
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
                _state.update { it.copy(removal = null) }
                if (vault.identities.value.isEmpty()) _state.update { it.copy(noIdentityLeft = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.AuthenticationCancelled) {
                _state.update { it.copy(removal = null) }
            } catch (e: AppError.PassphraseRequired) {
                _state.update { it.copy(removal = Removal(needsPassphrase = true)) }
            } catch (e: AppError.Forbidden) {
                val message = if (passphrase != null) context.getString(R.string.acc_wrong_passphrase) else e.userMessage(context)
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

    /**
     * Opens admin mode for the home node, after the user confirms with their screen lock. Opening it
     * proves the identity administers the node, which is then remembered, so the Admin tab shows:
     * a node's root user is an administrator through its key even when its account doesn't say so.
     * Only the home node for now; other nodes an identity administers can be added later.
     */
    fun startAdmin() {
        val identity = _state.value.identity ?: return
        val node = identity.homeNode ?: return
        if (_state.value.admin.busy) return
        if (!identity.backupVerified) {
            _state.update { it.copy(admin = AdminStart(error = context.getString(R.string.acc_admin_backup_first))) }
            return
        }
        _state.update { it.copy(admin = AdminStart(busy = true)) }
        viewModelScope.launch {
            try {
                val prompt = AuthPrompt(
                    context.getString(R.string.acc_admin_prompt_title),
                    context.getString(R.string.acc_admin_prompt_subtitle, node.host()),
                )
                adminSession.start(identity, node, prompt)
                if (!identity.administers) vault.update(identity.userId) { it.copy(administers = true) }
                _state.update { it.copy(admin = AdminStart(), adminOpened = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.AuthenticationCancelled) {
                _state.update { it.copy(admin = AdminStart()) }
            } catch (e: AppError) {
                val refused = e is AppError.Unauthorized || e is AppError.Forbidden
                // No longer an administrator there: the Admin tab goes, unless the account says otherwise.
                if (refused && identity.administers) vault.update(identity.userId) { it.copy(administers = false) }
                val message = if (refused) context.getString(R.string.acc_admin_not_admin, node.host()) else e.userMessage(context)
                _state.update { it.copy(admin = AdminStart(error = message)) }
            } catch (e: Exception) {
                _state.update { it.copy(admin = AdminStart(error = e.userMessage(context))) }
            }
        }
    }

    /** The app has shown the Admin tab. */
    fun adminOpenedSeen() = _state.update { it.copy(adminOpened = false) }

    private fun updateEdit(transform: (ProfileEdit) -> ProfileEdit) =
        _state.update { s -> s.edit?.let { s.copy(edit = transform(it)) } ?: s }

    // The switcher shows the identity by the name it has at its node.
    private suspend fun rememberName(identity: IdentityInfo, name: String?) {
        if (name != identity.name) vault.update(identity.userId) { it.copy(name = name) }
    }

    private fun looksLikeEmail(text: String): Boolean {
        val at = text.indexOf('@')
        return at > 0 && at < text.length - 1 && text.none { it.isWhitespace() }
    }
}

/** What the user types to confirm removing an identity from this phone: the first four characters of its id. */
fun removalCode(userId: String): String = userId.take(4)

/** The node's QR code, in the form the node's home page shows and the app's scanner reads. */
fun nodeQrPayload(node: NodeAddress): String =
    JSONObject().put("url", node.url).apply { node.nodeId?.let { put("id", it) } }.toString()

/** The node's host name, to name it in a sentence; its URL when that has none. */
fun NodeAddress.host(): String = runCatching { URI(url).host }.getOrNull() ?: url
