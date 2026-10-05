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

package io.bosonnetwork.identity.feature.devices

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.Id
import io.bosonnetwork.director.client.Device
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.call
import io.bosonnetwork.identity.core.designsystem.component.userMessage
import io.bosonnetwork.identity.core.model.AppError
import io.bosonnetwork.identity.core.model.IdentityInfo
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.vault.AuthPrompt
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

/** One device under the identity, as the home node lists it. */
data class DeviceView(
    val id: String,
    /** The name its owner gave it; may be empty. */
    val name: String,
    /** The app it runs; may be empty. */
    val app: String,
    /** [Device.KIND_APP] or [Device.KIND_PASSKEY]. */
    val kind: String,
    val admin: Boolean,
    /** Whether it can reset a forgotten passphrase. */
    val recoveryMethod: Boolean,
    val createdAt: Long,
    /** When the node last heard from it, in epoch milliseconds; 0 if never. */
    val lastSeen: Long,
    val lastAddress: String?,
    /** This app on this phone: it can't be removed from here. */
    val thisPhone: Boolean,
) {
    val isPasskey: Boolean get() = kind == Device.KIND_PASSKEY
}

/** The devices, in the groups the screen shows them in. */
data class DeviceGroups(
    /** This app's own device; null if the node doesn't list it (it was removed elsewhere). */
    val thisPhone: DeviceView?,
    val apps: List<DeviceView>,
    val passkeys: List<DeviceView>,
) {
    fun find(id: String): DeviceView? = all().firstOrNull { it.id == id }
}

sealed interface DevicesState {
    /** No home node yet, so no devices. */
    data object None : DevicesState
    data object Loading : DevicesState

    /** The user dismissed the unlock prompt. */
    data object Locked : DevicesState
    data class Ready(val groups: DeviceGroups) : DevicesState
    data class Failed(val message: String) : DevicesState
}

/** Renaming a device, as typed. */
data class Rename(
    val device: DeviceView,
    val name: String,
    val busy: Boolean = false,
    val error: String? = null,
) {
    /** The name to send, or null while it is empty or too long. */
    val validName: String? get() = name.trim().takeIf { it.length in 1..MAX_DEVICE_NAME }
}

/** Removing a device, once the user asked to. */
data class DeviceRemoval(
    val device: DeviceView,
    /** The account has a passphrase, and the node asked for it. */
    val needsPassphrase: Boolean = false,
    val passphrase: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

data class DevicesUiState(
    val identity: IdentityInfo? = null,
    val devices: DevicesState = DevicesState.None,
    /** Asking the node again while the list it gave last is still shown. */
    val refreshing: Boolean = false,
    /** Set while the rename dialog is open. */
    val rename: Rename? = null,
    /** Set while the remove dialog is open. */
    val removal: DeviceRemoval? = null,
    /**
     * Set when the user asked to remove a device before backing up the key: an identity without a
     * checked backup authorizes nothing, so the screen explains and offers the backup instead.
     */
    val backupNeeded: Boolean = false,
)

/** The longest device name the node takes. */
const val MAX_DEVICE_NAME = 128

/**
 * The devices of the identity in use, at its home node. Listing and renaming go through this app's
 * device key; removing a device is an act of authority, confirmed with the user key every time.
 */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val activeIdentity: ActiveIdentity,
    private val nodes: NodeConnections,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    private var loading: Job? = null
    private var working: Job? = null

    init {
        viewModelScope.launch {
            // The identity picked in the switcher. A change of its name alone doesn't ask the node again.
            activeIdentity.active.collect { identity ->
                val previous = _state.value.identity
                val switched = identity?.userId != previous?.userId
                if (switched) working?.cancel()
                _state.update {
                    if (switched) DevicesUiState(identity = identity, devices = it.devices) else it.copy(identity = identity)
                }
                if (switched || identity?.homeNode != previous?.homeNode || identity?.deviceId != previous?.deviceId) {
                    refresh(quiet = false)
                }
            }
        }
    }

    /**
     * Asks the home node again; after a dismissed prompt, this is the "Unlock" button. A list already
     * shown stays while the new one comes.
     */
    fun refresh() = refresh(quiet = true)

    private fun refresh(quiet: Boolean) {
        val identity = _state.value.identity
        // An answer still on its way may be for the identity shown before.
        loading?.cancel()
        if (identity?.homeNode == null) {
            _state.update { it.copy(devices = DevicesState.None, refreshing = false) }
            return
        }
        val keep = quiet && _state.value.devices is DevicesState.Ready
        _state.update { if (keep) it.copy(refreshing = true) else it.copy(devices = DevicesState.Loading, refreshing = false) }
        loading = viewModelScope.launch {
            val loaded = load(identity)
            _state.update { it.copy(devices = loaded, refreshing = false) }
        }
    }

    private suspend fun load(identity: IdentityInfo): DevicesState =
        try {
            val client = nodes.deviceClient(identity, unlockPrompt())
            val devices = call { client.listDevices().await() }
            DevicesState.Ready(group(devices.map { it.toView(identity) }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.AuthenticationCancelled) {
            DevicesState.Locked
        } catch (e: Exception) {
            DevicesState.Failed(e.userMessage(context))
        }

    fun startRename(device: DeviceView) = _state.update { it.copy(rename = Rename(device, device.name)) }

    fun onRenameChange(value: String) = _state.update { s -> s.rename?.let { s.copy(rename = it.copy(name = value, error = null)) } ?: s }

    fun cancelRename() {
        working?.cancel()
        _state.update { it.copy(rename = null) }
    }

    /** Renames the device. The name is all that changes, so this phone's key is enough. */
    fun rename() {
        val s = _state.value
        val identity = s.identity ?: return
        val rename = s.rename ?: return
        if (rename.busy) return
        val name = rename.validName ?: return
        if (name == rename.device.name) {
            _state.update { it.copy(rename = null) }
            return
        }
        _state.update { it.copy(rename = rename.copy(busy = true, error = null)) }
        working = viewModelScope.launch {
            try {
                val client = nodes.deviceClient(identity, unlockPrompt())
                val renamed = call { client.renameDevice(Id.of(rename.device.id), name).await() }.toView(identity)
                _state.update { st ->
                    val devices = (st.devices as? DevicesState.Ready)?.let { ready -> DevicesState.Ready(ready.groups.replace(renamed)) } ?: st.devices
                    st.copy(devices = devices, rename = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { st -> st.rename?.let { st.copy(rename = it.copy(busy = false, error = e.userMessage(context))) } ?: st }
            }
        }
    }

    /**
     * Opens removing [device]; this phone itself can't be removed from here. Without a checked backup
     * of the key, the screen explains why the identity can't do it yet instead.
     */
    fun startRemove(device: DeviceView) {
        if (device.thisPhone) return
        val identity = _state.value.identity ?: return
        if (!identity.backupVerified) {
            _state.update { it.copy(backupNeeded = true) }
            return
        }
        _state.update { it.copy(removal = DeviceRemoval(device)) }
    }

    fun onRemovePassphraseChange(value: String) =
        _state.update { s -> s.removal?.let { s.copy(removal = it.copy(passphrase = value, error = null)) } ?: s }

    fun cancelRemove() {
        working?.cancel()
        _state.update { it.copy(removal = null) }
    }

    fun dismissBackupNeeded() = _state.update { it.copy(backupNeeded = false) }

    /**
     * Removes the device, after the user confirms with their screen lock. When the node wants the
     * account passphrase, the dialog asks for it, and removing again sends it. The list is then
     * asked for again.
     */
    fun remove() {
        val s = _state.value
        val identity = s.identity ?: return
        val node = identity.homeNode ?: return
        val removal = s.removal ?: return
        if (removal.busy || removal.device.thisPhone || !identity.backupVerified) return
        val passphrase = removal.passphrase.ifEmpty { null }
        if (removal.needsPassphrase && passphrase == null) return

        _state.update { it.copy(removal = removal.copy(busy = true, error = null)) }
        working = viewModelScope.launch {
            try {
                val prompt = AuthPrompt(
                    context.getString(R.string.dev_remove_prompt_title),
                    context.getString(R.string.dev_remove_prompt_subtitle, displayName(removal.device), node.hostName()),
                )
                nodes.asUser(identity.userId, node, prompt) { client, _ ->
                    try {
                        call { client.removeDevice(Id.of(removal.device.id), passphrase).await() }
                    } catch (e: AppError.NotFound) {
                        // Already gone from the node.
                    }
                }
                _state.update { st ->
                    val devices = (st.devices as? DevicesState.Ready)?.let { DevicesState.Ready(it.groups.without(removal.device.id)) } ?: st.devices
                    st.copy(devices = devices, removal = null)
                }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.PassphraseRequired) {
                updateRemoval { it.copy(busy = false, needsPassphrase = true, passphrase = "", error = e.userMessage(context)) }
            } catch (e: AppError.Forbidden) {
                // With a passphrase given, the node refuses only a wrong one.
                val message = if (passphrase != null) context.getString(R.string.dev_wrong_passphrase) else e.userMessage(context)
                updateRemoval { it.copy(busy = false, passphrase = "", error = message) }
            } catch (e: Exception) {
                updateRemoval { it.copy(busy = false, error = e.userMessage(context)) }
            }
        }
    }

    private fun updateRemoval(transform: (DeviceRemoval) -> DeviceRemoval) =
        _state.update { s -> s.removal?.let { s.copy(removal = transform(it)) } ?: s }

    private fun unlockPrompt() = AuthPrompt(
        context.getString(R.string.dev_unlock_prompt_title),
        context.getString(R.string.dev_unlock_prompt_subtitle),
    )

    private fun displayName(device: DeviceView): String =
        device.name.ifBlank { device.app }.ifBlank { context.getString(R.string.dev_unnamed) }
}

private fun Device.toView(identity: IdentityInfo) = DeviceView(
    id = id.toString(),
    name = name,
    app = app,
    kind = kind,
    admin = isAdmin,
    recoveryMethod = isRecoveryMethod,
    createdAt = createdAt,
    lastSeen = lastSeen,
    lastAddress = lastAddress.orElse(null)?.takeIf { it.isNotBlank() },
    thisPhone = id.toString() == identity.deviceId,
)

/**
 * Groups [devices]: this phone, then the apps, then the passkeys; within a group, the one seen most
 * recently first.
 */
internal fun group(devices: List<DeviceView>): DeviceGroups {
    val others = devices.filter { !it.thisPhone }.sortedWith(compareByDescending<DeviceView> { it.lastSeen }.thenBy { it.name })
    return DeviceGroups(
        thisPhone = devices.firstOrNull { it.thisPhone },
        apps = others.filter { !it.isPasskey },
        passkeys = others.filter { it.isPasskey },
    )
}

private fun DeviceGroups.all(): List<DeviceView> = listOfNotNull(thisPhone) + apps + passkeys

private fun DeviceGroups.replace(device: DeviceView): DeviceGroups = group(all().map { if (it.id == device.id) device else it })

private fun DeviceGroups.without(id: String): DeviceGroups = group(all().filter { it.id != id })

/** The node's host name, to name it in a sentence; its URL when that has none. */
internal fun NodeAddress.hostName(): String = runCatching { URI(url).host }.getOrNull() ?: url
