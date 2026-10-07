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

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.Device
import io.bosonnetwork.director.client.LinkedIdentity
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.ThisDevice
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

/** An account at a provider (GitHub, Google, ...) linked to the identity: it signs in to the portal as the identity. */
data class LinkedView(
    /** The sign-in's id at the node, which unlinking takes. */
    val sessionId: String,
    /** The provider's name, or null if the node didn't say. */
    val provider: String?,
    val name: String?,
    val email: String?,
)

/** A browser passkey under the identity. */
data class PasskeyView(
    val id: String,
    val name: String,
    /** Whether it can reset a forgotten passphrase. */
    val recoveryMethod: Boolean,
)

/** The ways back in when the passphrase is forgotten, as the node counts them. */
data class RecoveryView(val accounts: Int, val passkeys: Int, val recoveryCodes: Int) {
    val any: Boolean get() = accounts + passkeys + recoveryCodes > 0
}

/** The account's security, as the home node has it. */
data class SecurityOverview(
    val passphraseProtected: Boolean,
    /** Null when the node didn't say. */
    val recovery: RecoveryView?,
    /** Null when the node didn't say. */
    val linked: List<LinkedView>?,
    val passkeys: List<PasskeyView>,
    /** The security log, newest first; null when the node didn't say. */
    val log: List<LogEntry>?,
)

sealed interface OverviewState {
    /** No home node yet, so no account to secure. */
    data object None : OverviewState
    data object Loading : OverviewState

    /** The user dismissed the unlock prompt. */
    data object Locked : OverviewState
    data class Ready(val overview: SecurityOverview) : OverviewState
    data class Failed(val message: String) : OverviewState
}

/** Unlinking an account, once the user asked to. */
data class Unlink(
    val account: LinkedView,
    /** The account has a passphrase, so unlinking needs it. */
    val needsPassphrase: Boolean = false,
    val passphrase: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

enum class RenewStep {
    /** What renewing does; renew or cancel. */
    CONFIRM,

    /** The account has a passphrase, and renewing needs it. */
    PASSPHRASE,
    WORKING,

    /** The node took the new key, but this phone couldn't store it; storing it is tried again. */
    SAVE_FAILED,
    DONE,
}

/** Renewing this phone's device key. */
data class Renewal(
    val step: RenewStep = RenewStep.CONFIRM,
    val needsPassphrase: Boolean = false,
    val passphrase: String = "",
    /** Done, but the old key couldn't be removed from the node; the user can remove it in Devices. */
    val oldKeyKept: Boolean = false,
    val error: String? = null,
)

data class SecurityUiState(
    val identity: IdentityInfo? = null,
    val overview: OverviewState = OverviewState.None,
    /** Asking the node again while what it said last is still shown. */
    val refreshing: Boolean = false,
    /** Set while the unlink dialog is open. */
    val unlink: Unlink? = null,
    /** Set while the renew dialog is open. */
    val renewal: Renewal? = null,
    /**
     * Set when the user asked for an act of authority before backing up the key: an identity without a
     * checked backup authorizes nothing, so the screen explains and offers the backup instead.
     */
    val backupNeeded: Boolean = false,
) {
    /** What needs the user, most urgent first: the summary, the section headers and the tab's badge read it. */
    val issues: List<Issue>
        get() = protectionIssues(identity, (overview as? OverviewState.Ready)?.overview)
}

/**
 * The security of the identity in use at its home node: the passphrase's state, the ways to recover
 * it, the linked accounts and passkeys, and the security log. Reading goes through this app's device
 * key. Unlinking an account and renewing this phone's device key are acts of authority, confirmed
 * with the user key every time and refused until the key's backup is checked.
 */
@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val activeIdentity: ActiveIdentity,
    private val vault: IdentityVault,
    private val nodes: NodeConnections,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SecurityUiState())
    val state: StateFlow<SecurityUiState> = _state.asStateFlow()

    private var loading: Job? = null
    private var working: Job? = null

    // The new device key once the node took it, kept until this phone stores it too: dropping it would
    // leave the phone without a device the node knows.
    private var renewedKey: Signature.KeyPair? = null

    init {
        viewModelScope.launch {
            // The identity picked in the switcher. A change of its name alone doesn't ask the node again.
            activeIdentity.active.collect { identity ->
                val previous = _state.value.identity
                val switched = identity?.userId != previous?.userId
                if (switched) {
                    working?.cancel()
                    forgetRenewedKey()
                }
                _state.update {
                    if (switched) SecurityUiState(identity = identity, overview = it.overview) else it.copy(identity = identity)
                }
                if (switched || identity?.homeNode != previous?.homeNode || identity?.deviceId != previous?.deviceId) {
                    refresh(quiet = false)
                }
            }
        }
    }

    /** Asks the home node again; after a dismissed prompt, this is the "Unlock" button. */
    fun refresh() = refresh(quiet = true)

    private fun refresh(quiet: Boolean) {
        val identity = _state.value.identity
        // An answer still on its way may be for the identity shown before.
        loading?.cancel()
        if (identity?.homeNode == null) {
            _state.update { it.copy(overview = OverviewState.None, refreshing = false) }
            return
        }
        val keep = quiet && _state.value.overview is OverviewState.Ready
        _state.update { if (keep) it.copy(refreshing = true) else it.copy(overview = OverviewState.Loading, refreshing = false) }
        loading = viewModelScope.launch {
            val loaded = load(identity)
            _state.update { it.copy(overview = loaded, refreshing = false) }
        }
    }

    private suspend fun load(identity: IdentityInfo): OverviewState =
        try {
            val client = nodes.deviceClient(identity, unlockPrompt())
            val profile = call { client.profile.await() }
            val devices = call { client.listDevices().await() }
            // Older nodes don't report these; the screen then just doesn't show them.
            val recovery = optional { client.recoveryMethods.await() }
            val linked = optional { client.listLinkedIdentities().await() }
            val log = optional { client.listSecurityEvents().await() }
            OverviewState.Ready(
                SecurityOverview(
                    passphraseProtected = profile.isPassphraseProtected,
                    recovery = recovery?.let { RecoveryView(it.oauth(), it.passkeys(), it.recoveryCodes()) },
                    linked = linked?.map { it.toView() },
                    passkeys = devices.filter { it.kind == Device.KIND_PASSKEY }.map { it.toPasskeyView() },
                    log = log?.map(::logEntry)?.sortedByDescending { it.at },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.AuthenticationCancelled) {
            OverviewState.Locked
        } catch (e: Exception) {
            OverviewState.Failed(e.userMessage(context))
        }

    private suspend fun <T> optional(block: suspend () -> T): T? =
        try {
            call(block)
        } catch (e: AppError) {
            null
        }

    fun dismissBackupNeeded() = _state.update { it.copy(backupNeeded = false) }

    // ---- Unlinking an account ----------------------------------------------------------------

    /** Opens unlinking [account]; without a checked backup of the key, the screen explains instead. */
    fun startUnlink(account: LinkedView) {
        val identity = _state.value.identity ?: return
        if (!identity.backupVerified) {
            _state.update { it.copy(backupNeeded = true) }
            return
        }
        _state.update { it.copy(unlink = Unlink(account, needsPassphrase = passphraseProtected())) }
    }

    fun onUnlinkPassphraseChange(value: String) =
        _state.update { s -> s.unlink?.let { s.copy(unlink = it.copy(passphrase = value, error = null)) } ?: s }

    fun cancelUnlink() {
        working?.cancel()
        _state.update { it.copy(unlink = null) }
    }

    /**
     * Unlinks the account, after the user confirms with their screen lock. A linked account can reset
     * the passphrase, so the passphrase guards the list: the dialog asks for it when the account has one
     * or the node asks for it.
     */
    fun unlink() {
        val s = _state.value
        val identity = s.identity ?: return
        val node = identity.homeNode ?: return
        val unlink = s.unlink ?: return
        if (unlink.busy || !identity.backupVerified) return
        val passphrase = unlink.passphrase.ifEmpty { null }
        if (unlink.needsPassphrase && passphrase == null) return

        updateUnlink { it.copy(busy = true, error = null) }
        working = viewModelScope.launch {
            try {
                val provider = unlink.account.provider ?: context.getString(R.string.sec_linked_unknown_provider)
                val prompt = AuthPrompt(
                    context.getString(R.string.sec_unlink_prompt_title),
                    context.getString(R.string.sec_unlink_prompt_subtitle, provider, node.hostName()),
                )
                nodes.asUser(identity.userId, node, prompt) { client, _ ->
                    try {
                        call { client.disconnectLinkedIdentity(Id.of(unlink.account.sessionId), passphrase).await() }
                    } catch (e: AppError.NotFound) {
                        // Already unlinked.
                    }
                }
                _state.update { st ->
                    val overview = (st.overview as? OverviewState.Ready)?.overview?.let { o ->
                        OverviewState.Ready(o.copy(linked = o.linked?.filter { it.sessionId != unlink.account.sessionId }))
                    } ?: st.overview
                    st.copy(overview = overview, unlink = null)
                }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.PassphraseRequired) {
                updateUnlink { it.copy(busy = false, needsPassphrase = true, passphrase = "", error = e.userMessage(context)) }
            } catch (e: AppError.Forbidden) {
                // With a passphrase given, the node refuses only a wrong one.
                val message = if (passphrase != null) context.getString(R.string.sec_wrong_passphrase) else e.userMessage(context)
                updateUnlink { it.copy(busy = false, passphrase = "", error = message) }
            } catch (e: Exception) {
                updateUnlink { it.copy(busy = false, error = e.userMessage(context)) }
            }
        }
    }

    private fun updateUnlink(transform: (Unlink) -> Unlink) =
        _state.update { s -> s.unlink?.let { s.copy(unlink = transform(it)) } ?: s }

    // ---- Renewing this phone's device key ----------------------------------------------------

    /**
     * Opens renewing this phone's device key. It needs the home node and this phone's device there,
     * and, as an act of authority, a checked backup of the key.
     */
    fun startRenew() {
        val identity = _state.value.identity ?: return
        if (identity.homeNode == null || identity.deviceId == null) return
        if (!identity.backupVerified) {
            _state.update { it.copy(backupNeeded = true) }
            return
        }
        _state.update { it.copy(renewal = Renewal(needsPassphrase = passphraseProtected())) }
    }

    fun onRenewPassphraseChange(value: String) =
        _state.update { s -> s.renewal?.let { s.copy(renewal = it.copy(passphrase = value, error = null)) } ?: s }

    /**
     * Closes the renew dialog. When the node already took the new key but this phone couldn't store
     * it, the key is dropped: the phone then has no device at the node any more, and joins it again.
     */
    fun closeRenew() {
        working?.cancel()
        forgetRenewedKey()
        _state.update { it.copy(renewal = null) }
    }

    /**
     * Renews this phone's device key, after the user confirms with their screen lock: in one act of
     * authority, a new key is registered as this phone's device and the old one removed; then the new
     * key replaces the old in the vault, and the routine connection is dropped, so the next call uses
     * the new key. An account with a passphrase is asked for it first; one the node asks for anyway is
     * asked for, and renewing again sends it. If the node took the new key but storing it failed,
     * renewing again only stores it.
     */
    fun renew() {
        val s = _state.value
        val identity = s.identity ?: return
        val node = identity.homeNode ?: return
        val oldDeviceId = identity.deviceId ?: return
        val renewal = s.renewal ?: return
        if (!identity.backupVerified) return
        if (renewedKey != null) {
            if (renewal.step != RenewStep.SAVE_FAILED) return
            updateRenewal { it.copy(step = RenewStep.WORKING, error = null) }
            working = viewModelScope.launch { store(identity) }
            return
        }
        val passphrase = when (renewal.step) {
            RenewStep.CONFIRM -> {
                if (renewal.needsPassphrase) {
                    updateRenewal { it.copy(step = RenewStep.PASSPHRASE, passphrase = "", error = null) }
                    return
                }
                null
            }
            RenewStep.PASSPHRASE -> renewal.passphrase.ifEmpty { return }
            else -> return
        }
        val back = renewal.step
        updateRenewal { it.copy(step = RenewStep.WORKING, error = null) }
        working = viewModelScope.launch {
            val newKey = Signature.KeyPair.random()
            try {
                val prompt = AuthPrompt(
                    context.getString(R.string.sec_renew_prompt_title),
                    context.getString(R.string.sec_renew_prompt_subtitle, node.hostName()),
                )
                val oldRemoved = nodes.asUser(identity.userId, node, prompt) { client, _ ->
                    call { client.registerDevice(newKey, ThisDevice.name, ThisDevice.APP_NAME, passphrase).await() }
                    // From here on the node knows the new key: it is kept, whatever happens next.
                    renewedKey = newKey
                    try {
                        call { client.removeDevice(Id.of(oldDeviceId), passphrase).await() }
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AppError.NotFound) {
                        // Already gone from the node.
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                updateRenewal { it.copy(oldKeyKept = !oldRemoved, passphrase = "") }
                store(identity)
            } catch (e: CancellationException) {
                if (renewedKey !== newKey) newKey.destroy()
                throw e
            } catch (e: Exception) {
                if (renewedKey !== newKey) newKey.destroy()
                val (step, message) = when (e) {
                    is AppError.PassphraseRequired -> {
                        updateRenewal { it.copy(needsPassphrase = true) }
                        RenewStep.PASSPHRASE to if (passphrase != null) e.userMessage(context) else null
                    }
                    // With a passphrase given, the node refuses only a wrong one.
                    is AppError.Forbidden ->
                        back to if (passphrase != null) context.getString(R.string.sec_wrong_passphrase) else e.userMessage(context)
                    else -> back to e.userMessage(context)
                }
                updateRenewal { it.copy(step = step, passphrase = "", error = message) }
            }
        }
    }

    // Stores the renewed key in the vault, makes it this phone's device, and drops the connection that
    // still holds the old key.
    private suspend fun store(identity: IdentityInfo) {
        val key = renewedKey ?: return
        try {
            val prompt = AuthPrompt(
                context.getString(R.string.sec_renew_store_prompt_title),
                context.getString(R.string.sec_renew_store_prompt_subtitle),
            )
            vault.replaceDeviceKey(identity.userId, key, prompt)
            vault.update(identity.userId) { it.copy(deviceId = Id.of(key.publicKey().bytes()).toString()) }
            nodes.forget(identity.userId)
            forgetRenewedKey()
            updateRenewal { it.copy(step = RenewStep.DONE, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateRenewal { it.copy(step = RenewStep.SAVE_FAILED, error = e.userMessage(context)) }
        }
    }

    private fun forgetRenewedKey() {
        renewedKey?.destroy()
        renewedKey = null
    }

    private fun updateRenewal(transform: (Renewal) -> Renewal) =
        _state.update { s -> s.renewal?.let { s.copy(renewal = transform(it)) } ?: s }

    override fun onCleared() {
        forgetRenewedKey()
    }

    private fun passphraseProtected(): Boolean =
        ((_state.value.overview as? OverviewState.Ready)?.overview?.passphraseProtected) == true

    private fun unlockPrompt() = AuthPrompt(
        context.getString(R.string.sec_unlock_prompt_title),
        context.getString(R.string.sec_unlock_prompt_subtitle),
    )
}

private fun LinkedIdentity.toView() = LinkedView(
    sessionId = sessionId.toString(),
    provider = provider.orElse(null)?.takeIf { it.isNotBlank() }?.let(::providerName),
    name = name.orElse(null)?.takeIf { it.isNotBlank() },
    email = email.orElse(null)?.takeIf { it.isNotBlank() },
)

private fun Device.toPasskeyView() = PasskeyView(id = id.toString(), name = name, recoveryMethod = isRecoveryMethod)

/** The node's host name, to name it in a sentence; its URL when that has none. */
internal fun NodeAddress.hostName(): String = runCatching { URI(url).host }.getOrNull() ?: url
