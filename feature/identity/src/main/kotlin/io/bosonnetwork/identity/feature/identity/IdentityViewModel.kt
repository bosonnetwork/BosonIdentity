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

package io.bosonnetwork.identity.feature.identity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.Id
import io.bosonnetwork.director.client.NodeStatus
import io.bosonnetwork.director.client.SuperNodeCode
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.AdminAccess
import io.bosonnetwork.identity.core.boson.AvatarStore
import io.bosonnetwork.identity.core.boson.CardContent
import io.bosonnetwork.identity.core.boson.CardStatus
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.NodeLogos
import io.bosonnetwork.identity.core.boson.ProfileCards
import io.bosonnetwork.identity.core.boson.ProfileSync
import io.bosonnetwork.identity.core.boson.SyncOutcome
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

/** The identity's profile, as its home node keeps it. */
data class ProfileView(
    val name: String?,
    val bio: String?,
    val email: String?,
    /** The avatar's bnr:// URI at the home node, or null when the account has none. */
    val avatar: String?,
    val plan: String,
    val passphraseProtected: Boolean,
    /** The account is marked as an administrator of the node. */
    val admin: Boolean,
)

/** What the home node says about itself. */
data class HomeNodeView(
    /** The node's id, from its status; null when the status couldn't be read. */
    val nodeId: String?,
    val name: String?,
    /** Whether the node is running (not starting or shutting down). */
    val running: Boolean,
    /** Whether it offers the web gateway that publishes profile cards. */
    val hasGateway: Boolean,
    /** Its messaging service, which the profile card names, if it offers one. */
    val messagingPeer: String?,
    val logo: String?,
)

sealed interface NodeState {
    /** No home node yet. */
    data object None : NodeState
    data object Loading : NodeState

    /** The user dismissed the unlock prompt. */
    data object Locked : NodeState
    data class Ready(val profile: ProfileView, val node: HomeNodeView) : NodeState

    /** The node didn't answer ([unreachable]), or refused. */
    data class Failed(val message: String, val unreachable: Boolean) : NodeState
}

/** The public profile card, as far as the app knows. */
sealed interface CardState {
    /** Not known yet: no home node, or the node not asked yet. */
    data object Unknown : CardState
    data object Checking : CardState

    /** The home node has no web gateway, so it can't publish cards. */
    data object Unavailable : CardState
    data object Off : CardState
    data class On(val version: Int) : CardState
    data class Failed(val message: String) : CardState
}

/** Which change of the card the user is confirming. */
enum class CardConfirm { PUBLISH, STOP }

data class CardUi(
    val state: CardState = CardState.Unknown,
    val busy: Boolean = false,
    val confirm: CardConfirm? = null,
    val error: String? = null,
    /** The published card no longer says what the profile does: "Update" publishes it again. */
    val outdated: Boolean = false,
)

/** The profile being edited, as typed. */
data class ProfileEdit(
    val name: String,
    val bio: String,
    val email: String,
    val passphrase: String = "",
    /** Whether saving needs the account passphrase: the account has one, or the node asked for it. */
    val needsPassphrase: Boolean = false,
    /** Saving also updates the published profile card. */
    val updatesCard: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/** A picture picked for the avatar, prepared, and waiting for the user to confirm it. */
class AvatarPick(val image: ByteArray, val preview: Bitmap, val busy: Boolean = false, val error: String? = null) {
    fun copy(busy: Boolean = this.busy, error: String? = this.error) = AvatarPick(image, preview, busy, error)
}

data class IdentityUiState(
    val identity: IdentityInfo? = null,
    val node: NodeState = NodeState.None,
    /** Asking the node again while what it said last is still shown. */
    val refreshing: Boolean = false,
    val logo: Bitmap? = null,
    /** The avatar's image, as downloaded; null while there is none to show (the identicon stands in). */
    val avatar: ByteArray? = null,
    val card: CardUi = CardUi(),
    /** Set while the edit dialog is open. */
    val edit: ProfileEdit? = null,
    /** The avatar's menu (choose a photo, remove it) is open. */
    val avatarMenu: Boolean = false,
    /** A picked picture waiting to be confirmed as the avatar. */
    val avatarPick: AvatarPick? = null,
    /** Preparing a picked picture, or removing the avatar. */
    val avatarBusy: Boolean = false,
    /** Why the avatar couldn't be changed, until dismissed. */
    val avatarError: String? = null,
    /** What the last sync of the profile wants the user to know, until dismissed. */
    val syncNote: String? = null,
) {
    /** The avatar can be changed: it is part of the profile on this phone, with or without a home node. */
    val canChangeAvatar: Boolean
        get() = identity != null && !avatarBusy && avatarPick == null

    /** Whether the identity administers its home node, as far as the app knows: it gets the Dashboard. */
    val administers: Boolean
        get() = identity?.administers == true || (node as? NodeState.Ready)?.profile?.admin == true
}

/**
 * The Identity tab: who the identity is (its profile, kept on this phone first, and its public profile
 * card) and where it lives (the home node and its status).
 *
 * The profile lives in the vault: it is shown and edited with or without a home node. With one, it is
 * synced with the node (through this app's device key, see [ProfileSync]) when the tab opens, on Refresh,
 * after every change, and before the card is published. Publishing the card is an act of authority,
 * confirmed with the user key.
 */
@HiltViewModel
class IdentityViewModel @Inject constructor(
    private val vault: IdentityVault,
    private val activeIdentity: ActiveIdentity,
    private val nodes: NodeConnections,
    private val cards: ProfileCards,
    private val logos: NodeLogos,
    private val avatars: AvatarStore,
    private val adminAccess: AdminAccess,
    private val avatarImages: AvatarImages,
    private val profileSync: ProfileSync,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(IdentityUiState())
    val state: StateFlow<IdentityUiState> = _state.asStateFlow()

    private var loading: Job? = null
    private var working: Job? = null

    init {
        viewModelScope.launch {
            // The identity picked in the switcher. A change of its profile alone doesn't ask the node again.
            activeIdentity.active.collect { identity ->
                val previous = _state.value.identity
                val switched = identity?.userId != previous?.userId
                val moved = identity?.homeNode != previous?.homeNode
                if (switched || moved) working?.cancel()
                _state.update {
                    if (switched || moved) {
                        IdentityUiState(identity = identity, avatar = identity?.userId?.let { id -> avatars.avatars.value[id] })
                    } else {
                        it.copy(identity = identity, node = it.node.withProfile(identity))
                    }
                }
                if (switched || moved) refresh()
            }
        }
        viewModelScope.launch {
            // The picture kept for the identity shows at once.
            avatars.load()
            combine(activeIdentity.active, avatars.avatars) { identity, all -> identity?.userId?.let { all[it] } }
                .collect { avatar -> _state.update { it.copy(avatar = avatar) } }
        }
    }

    // The node's view with the profile fields as the vault has them now.
    private fun NodeState.withProfile(identity: IdentityInfo?): NodeState {
        if (this !is NodeState.Ready || identity == null) return this
        val fields = identity.profile.fields
        return copy(profile = profile.copy(name = fields.name, bio = fields.bio, email = fields.email,
            avatar = fields.avatar?.let { avatarUri(identity, node) }))
    }

    private fun avatarUri(identity: IdentityInfo, node: HomeNodeView): String? =
        (node.nodeId ?: identity.homeNode?.nodeId)?.let { CardContent.avatarUri(it, identity.userId) }

    private fun current(): IdentityInfo? =
        _state.value.identity?.userId?.let { id -> vault.identities.value.firstOrNull { it.userId == id } }

    /**
     * Syncs the profile and asks the home node again (its status, the account, the card); after a
     * dismissed prompt, this is the "Unlock" action. Without a home node there is nothing to ask.
     */
    fun refresh() {
        val identity = _state.value.identity ?: return
        // An answer still on its way may be for the identity shown before.
        loading?.cancel()
        val homeNode = identity.homeNode
        if (homeNode == null) {
            _state.update { it.copy(node = NodeState.None, refreshing = false, card = CardUi(), syncNote = null) }
            return
        }
        // What the node said last stays on screen while it is asked again.
        val quiet = _state.value.node is NodeState.Ready
        _state.update { if (quiet) it.copy(refreshing = true) else it.copy(node = NodeState.Loading) }
        loading = viewModelScope.launch {
            val note = syncProfile(identity.userId, null)
            val fresh = current() ?: identity
            val node = load(fresh)
            _state.update { it.copy(node = node, refreshing = false, syncNote = note) }
            if (node is NodeState.Ready) {
                loadLogo(homeNode, node.node.logo)
                loadCard(fresh, node.node)
            }
        }
    }

    private fun unlockPrompt() = AuthPrompt(
        context.getString(R.string.id_unlock_prompt_title),
        context.getString(R.string.id_unlock_prompt_subtitle),
    )

    /**
     * Syncs the profile of [userId] with its home node, and says what the user should know, or null.
     * A dismissed prompt or an unreachable node are left to [load], which reports them.
     */
    private suspend fun syncProfile(userId: String, passphrase: String?): String? =
        try {
            when (val outcome = profileSync.sync(userId, unlockPrompt(), passphrase)) {
                is SyncOutcome.Merged -> context.getString(R.string.id_sync_merged)
                SyncOutcome.NeedsPassphrase -> context.getString(R.string.id_sync_needs_passphrase)
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.AuthenticationCancelled) {
            null
        } catch (e: AppError.Network) {
            null
        } catch (e: AppError.Timeout) {
            null
        } catch (e: Exception) {
            context.getString(R.string.id_sync_failed, e.userMessage(context))
        }

    private suspend fun load(identity: IdentityInfo): NodeState =
        try {
            val client = nodes.deviceClient(identity, unlockPrompt())
            val profile = call { client.profile.await() }
            // The node's own description is a nicety: the profile shows without it.
            val status = try {
                call { client.nodeStatus.await() }
            } catch (e: AppError) {
                null
            }
            val node = homeNodeView(status)
            val fields = identity.profile.fields
            val view = ProfileView(
                name = fields.name,
                bio = fields.bio,
                email = fields.email,
                avatar = fields.avatar?.let { avatarUri(identity, node) },
                plan = profile.planName,
                passphraseProtected = profile.isPassphraseProtected,
                admin = profile.isAdmin,
            )
            // An account marked as an administrator gets the Dashboard, with no prompt of its own.
            adminAccess.reportProfile(identity.userId, profile.isAdmin)
            NodeState.Ready(view, node)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.AuthenticationCancelled) {
            NodeState.Locked
        } catch (e: Exception) {
            NodeState.Failed(e.userMessage(context), unreachable = e is AppError.Network || e is AppError.Timeout)
        }

    private fun homeNodeView(status: NodeStatus?): HomeNodeView =
        HomeNodeView(
            nodeId = status?.nodeId?.toString(),
            name = status?.name?.orElse(null)?.takeIf { it.isNotBlank() },
            running = status?.isRunning ?: true,
            hasGateway = status?.getService(NodeStatus.Service.WEB_GATEWAY)?.isPresent == true,
            messagingPeer = status?.getService(NodeStatus.Service.PHOTON_MESSAGING)?.orElse(null)?.peerId?.toString(),
            logo = status?.logo?.orElse(null),
        )

    private suspend fun loadLogo(node: NodeAddress, logo: String?) {
        val bytes = logos.load(node, logo) ?: return
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        _state.update { it.copy(logo = bitmap) }
    }

    private suspend fun loadCard(identity: IdentityInfo, node: HomeNodeView) {
        if (!node.hasGateway) {
            _state.update { it.copy(card = it.card.copy(state = CardState.Unavailable)) }
            return
        }
        // A quiet refresh keeps showing what was known; a first look says it is checking.
        if (_state.value.card.state !is CardState.On && _state.value.card.state != CardState.Off) {
            _state.update { it.copy(card = it.card.copy(state = CardState.Checking)) }
        }
        var outdated = false
        val card = try {
            when (val status = cards.status(identity, unlockPrompt())) {
                CardStatus.NotPublished -> CardState.Off
                is CardStatus.Published -> {
                    // Changed elsewhere (another phone, another app, the portal), or here while unpublished.
                    outdated = cardContent(identity, node)?.let { ProfileCards.outdated(status.content, it) } == true
                    CardState.On(status.version)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.ServiceUnavailable) {
            CardState.Unavailable
        } catch (e: Exception) {
            CardState.Failed(e.userMessage(context))
        }
        _state.update { it.copy(card = it.card.copy(state = card, outdated = outdated)) }
    }

    // ---- The public profile card ---------------------------------------------------------------

    /** The user flipped the card's switch: what turning it [on] or off does is confirmed first. */
    fun requestCard(on: Boolean) {
        val identity = _state.value.identity ?: return
        if (_state.value.card.busy) return
        if (on && !identity.backupVerified) {
            _state.update { it.copy(card = it.card.copy(error = context.getString(R.string.id_backup_first))) }
            return
        }
        _state.update { it.copy(card = it.card.copy(confirm = if (on) CardConfirm.PUBLISH else CardConfirm.STOP, error = null)) }
    }

    fun cancelCard() = _state.update { it.copy(card = it.card.copy(confirm = null)) }

    /** Publishes or stops publishing the card, as confirmed. Publishing asks for the user key. */
    fun confirmCard() {
        val confirm = _state.value.card.confirm ?: return
        _state.update { it.copy(card = it.card.copy(confirm = null)) }
        when (confirm) {
            CardConfirm.PUBLISH -> publishCard()
            CardConfirm.STOP -> stopCard()
        }
    }

    /** Publishes the card again from the profile as it is now: the "Update" of an out-of-date card. */
    fun updateCard() {
        if (_state.value.card.state is CardState.On) publishCard()
    }

    // Syncs the profile first, so that the card carries its latest revision, then publishes it.
    private fun publishCard() {
        val s = _state.value
        val identity = s.identity ?: return
        val ready = s.node as? NodeState.Ready ?: return
        if (s.card.busy) return
        _state.update { it.copy(card = it.card.copy(busy = true, error = null)) }
        working = viewModelScope.launch {
            try {
                syncProfile(identity.userId, null)
                val fresh = current() ?: identity
                val content = cardContent(fresh, ready.node) ?: return@launch
                val prompt = AuthPrompt(
                    context.getString(R.string.id_card_prompt_title),
                    context.getString(R.string.id_card_prompt_subtitle),
                )
                val version = cards.publish(fresh, content, prompt)
                _state.update { it.copy(card = CardUi(state = CardState.On(version)), node = it.node.withProfile(current())) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.AuthenticationCancelled) {
                _state.update { it.copy(card = it.card.copy(busy = false)) }
            } catch (e: Exception) {
                _state.update { it.copy(card = it.card.copy(busy = false, error = e.userMessage(context))) }
            } finally {
                _state.update { if (it.card.busy) it.copy(card = it.card.copy(busy = false)) else it }
            }
        }
    }

    private fun stopCard() {
        val identity = _state.value.identity ?: return
        _state.update { it.copy(card = it.card.copy(busy = true, error = null)) }
        working = viewModelScope.launch {
            try {
                cards.unpublish(identity, unlockPrompt())
                _state.update { it.copy(card = CardUi(state = CardState.Off)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.AuthenticationCancelled) {
                _state.update { it.copy(card = it.card.copy(busy = false)) }
            } catch (e: Exception) {
                _state.update { it.copy(card = it.card.copy(busy = false, error = e.userMessage(context))) }
            }
        }
    }

    // The card for the identity's profile at [node]; null while the node's id is unknown (its status unread).
    private fun cardContent(identity: IdentityInfo, node: HomeNodeView): CardContent? {
        val nodeId = node.nodeId ?: identity.homeNode?.nodeId ?: return null
        return CardContent.of(identity, nodeId, node.messagingPeer)
    }

    /**
     * After the profile changed and synced: a published card that no longer says what the profile does is
     * published again (the user key signs it); one that can't be is reported, the profile staying saved.
     */
    private suspend fun republishIfPublished(userId: String, before: CardContent?) {
        val s = _state.value
        if (s.card.state !is CardState.On) return
        val ready = s.node as? NodeState.Ready ?: return
        val identity = current() ?: return
        val content = cardContent(identity, ready.node) ?: return
        // The email isn't on the card, and a new picture keeps its address: such changes leave it as it is.
        if (before != null && before.copy(revision = null) == content.copy(revision = null)) return
        try {
            val prompt = AuthPrompt(
                context.getString(R.string.id_card_prompt_title),
                context.getString(R.string.id_card_prompt_subtitle),
            )
            val version = cards.publish(identity, content, prompt)
            _state.update { it.copy(card = CardUi(state = CardState.On(version))) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = if (e is AppError.AuthenticationCancelled) null else e.userMessage(context)
            _state.update {
                it.copy(card = it.card.copy(outdated = true,
                    error = message?.let { m -> context.getString(R.string.id_card_update_failed, m) }))
            }
        }
    }

    // ---- The avatar ----------------------------------------------------------------------------

    /** The avatar of an identity that has one was tapped: its menu opens (one without goes to the picker). */
    fun openAvatarMenu() {
        if (_state.value.canChangeAvatar) _state.update { it.copy(avatarMenu = true) }
    }

    fun closeAvatarMenu() = _state.update { it.copy(avatarMenu = false) }

    fun dismissAvatarError() = _state.update { it.copy(avatarError = null) }

    fun cancelAvatar() {
        if (_state.value.avatarPick?.busy == true) working?.cancel()
        _state.update { it.copy(avatarPick = null) }
    }

    /** A picture came back from the photo picker, or none ([uri] null): prepared, then shown to confirm. */
    fun onAvatarPicked(uri: Uri?) {
        _state.update { it.copy(avatarMenu = false) }
        if (uri == null || !_state.value.canChangeAvatar) return
        _state.update { it.copy(avatarBusy = true, avatarError = null) }
        working = viewModelScope.launch {
            try {
                val prepared = avatarImages.prepare(uri)
                _state.update { it.copy(avatarBusy = false, avatarPick = AvatarPick(prepared.image, prepared.preview)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(avatarBusy = false, avatarError = e.userMessage(context)) }
            }
        }
    }

    /** Makes the confirmed picture the avatar: kept on this phone, then synced and on the card. */
    fun confirmAvatar() {
        val pick = _state.value.avatarPick ?: return
        if (pick.busy) return
        _state.update { it.copy(avatarPick = pick.copy(busy = true, error = null)) }
        changeAvatar(pick.image)
    }

    /** Removes the avatar; the identicon stands in again. */
    fun removeAvatar() {
        _state.update { it.copy(avatarMenu = false) }
        if (!_state.value.canChangeAvatar) return
        _state.update { it.copy(avatarBusy = true, avatarError = null) }
        changeAvatar(null)
    }

    // Keeps [image] (or none) as the avatar on this phone, then syncs the profile and updates the card.
    private fun changeAvatar(image: ByteArray?) {
        val identity = _state.value.identity ?: return
        working = viewModelScope.launch {
            try {
                val before = cardBefore()
                profileSync.setAvatar(identity.userId, image)
                _state.update { it.copy(avatarPick = null, avatarBusy = false, node = it.node.withProfile(current())) }
                afterChange(identity.userId, null, before)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Kept on this phone already; the node refused it, for a reason the user should see.
                _state.update { it.copy(avatarBusy = false, avatarPick = null, avatarError = e.userMessage(context)) }
            }
        }
    }

    // After the profile changed on this phone: synced with the home node, if any, and the card updated.
    // Returns the sync's outcome, or null when it could not run (no node, or the node unreachable).
    private suspend fun afterChange(userId: String, passphrase: String?, before: CardContent?): SyncOutcome? {
        if (current()?.homeNode == null) return null
        val outcome = try {
            profileSync.sync(userId, unlockPrompt(), passphrase)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError) {
            // The node out of reach (or the unlock dismissed): the change stays here, synced later. Any
            // other refusal is for the user to see.
            if (e !is AppError.Network && e !is AppError.Timeout && e !is AppError.ServerError &&
                e !is AppError.ServiceUnavailable && e !is AppError.AuthenticationCancelled) throw e
            _state.update { it.copy(syncNote = context.getString(R.string.id_sync_later)) }
            return null
        }
        _state.update {
            it.copy(
                node = it.node.withProfile(current()),
                syncNote = when (outcome) {
                    is SyncOutcome.Merged -> context.getString(R.string.id_sync_merged)
                    SyncOutcome.NeedsPassphrase -> context.getString(R.string.id_sync_needs_passphrase)
                    else -> null
                },
            )
        }
        if (outcome != SyncOutcome.NeedsPassphrase) republishIfPublished(userId, before)
        return outcome
    }

    fun dismissSyncNote() = _state.update { it.copy(syncNote = null) }

    // What the card says before a change: the change republishes it only if that is different after.
    private fun cardBefore(): CardContent? {
        val ready = _state.value.node as? NodeState.Ready ?: return null
        return current()?.let { cardContent(it, ready.node) }
    }

    // ---- Editing the profile -------------------------------------------------------------------

    /** Opens the editor: the profile is the one on this phone, editable with or without a home node. */
    fun startEdit() {
        val s = _state.value
        val identity = current() ?: s.identity ?: return
        val fields = identity.profile.fields
        _state.update {
            it.copy(
                edit = ProfileEdit(
                    fields.name.orEmpty(),
                    fields.bio.orEmpty(),
                    fields.email.orEmpty(),
                    needsPassphrase = (s.node as? NodeState.Ready)?.profile?.passphraseProtected == true &&
                        identity.homeNode != null,
                    updatesCard = s.card.state is CardState.On,
                ),
            )
        }
    }

    fun onNameChange(value: String) = updateEdit { it.copy(name = value, error = null) }
    fun onBioChange(value: String) = updateEdit { it.copy(bio = value, error = null) }
    fun onEmailChange(value: String) = updateEdit { it.copy(email = value, error = null) }
    fun onPassphraseChange(value: String) = updateEdit { it.copy(passphrase = value, error = null) }

    fun cancelEdit() {
        working?.cancel()
        _state.update { it.copy(edit = null) }
    }

    /**
     * Saves the profile on this phone, then syncs it with the home node, if any. The account passphrase,
     * when the account has one, goes with the sync; a wrong or missing one keeps the dialog open, the
     * change already saved here. A published card is updated (the user key signs it).
     */
    fun save() {
        val s = _state.value
        val identity = current() ?: s.identity ?: return
        val edit = s.edit ?: return
        if (edit.busy) return

        val name = edit.name.trim().ifEmpty { null }
        val bio = edit.bio.trim().ifEmpty { null }
        val email = edit.email.trim().ifEmpty { null }
        if (email != null && !looksLikeEmail(email)) {
            updateEdit { it.copy(error = context.getString(R.string.id_edit_invalid_email)) }
            return
        }
        val fields = identity.profile.fields
        val changed = name != fields.name || bio != fields.bio || email != fields.email
        // Unchanged and already synced: nothing to do. Unchanged but not synced: the save syncs it.
        if (!changed && (identity.homeNode == null || !identity.profile.dirty)) {
            _state.update { it.copy(edit = null) }
            return
        }
        val passphrase = edit.passphrase.ifEmpty { null }
        if (identity.homeNode != null && edit.needsPassphrase && passphrase == null) return

        updateEdit { it.copy(busy = true, error = null) }
        working = viewModelScope.launch {
            try {
                val before = cardBefore()
                if (changed) profileSync.edit(identity.userId, name, bio, email)
                _state.update { it.copy(node = it.node.withProfile(current())) }
                when (afterChange(identity.userId, passphrase, before)) {
                    SyncOutcome.NeedsPassphrase -> updateEdit {
                        it.copy(busy = false, needsPassphrase = true, passphrase = "",
                            error = if (passphrase != null) context.getString(R.string.id_wrong_passphrase)
                            else AppError.PassphraseRequired().userMessage(context))
                    }
                    else -> _state.update { it.copy(edit = null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Forbidden) {
                // With a passphrase given, the node refuses only a wrong one.
                val message = if (passphrase != null) context.getString(R.string.id_wrong_passphrase) else e.userMessage(context)
                updateEdit { it.copy(busy = false, passphrase = "", needsPassphrase = true, error = message) }
            } catch (e: Exception) {
                updateEdit { it.copy(busy = false, error = e.userMessage(context)) }
            }
        }
    }

    private fun updateEdit(transform: (ProfileEdit) -> ProfileEdit) =
        _state.update { s -> s.edit?.let { s.copy(edit = transform(it)) } ?: s }

    private fun looksLikeEmail(text: String): Boolean {
        val at = text.indexOf('@')
        return at > 0 && at < text.length - 1 && text.none { it.isWhitespace() }
    }
}

/**
 * The node's QR code, the Boson string `boson:supernode:1:<nodeId>:<url>` the portal shows and the
 * scanners read. The id pins the node for whoever scans it, so a code needs it: [nodeId] is the node's.
 */
fun nodeQrPayload(node: NodeAddress, nodeId: String): String =
    SuperNodeCode.of(Id.of(nodeId), node.url).toString()

/** The node's host name, to name it in a sentence; its URL when that has none. */
fun NodeAddress.host(): String = runCatching { URI(url).host }.getOrNull() ?: url
