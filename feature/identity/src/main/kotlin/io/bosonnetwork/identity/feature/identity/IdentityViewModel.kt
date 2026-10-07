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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.director.client.AvatarRefresh
import io.bosonnetwork.director.client.NodeStatus
import io.bosonnetwork.director.client.ProfileUpdate
import io.bosonnetwork.identity.core.boson.ActiveIdentity
import io.bosonnetwork.identity.core.boson.AdminAccess
import io.bosonnetwork.identity.core.boson.AvatarCache
import io.bosonnetwork.identity.core.boson.CardContent
import io.bosonnetwork.identity.core.boson.CardStatus
import io.bosonnetwork.identity.core.boson.NodeConnections
import io.bosonnetwork.identity.core.boson.NodeLogos
import io.bosonnetwork.identity.core.boson.ProfileCards
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
import org.json.JSONObject

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
) {
    /** Whether the identity administers its home node, as far as the app knows: it gets the Dashboard. */
    val administers: Boolean
        get() = identity?.administers == true || (node as? NodeState.Ready)?.profile?.admin == true
}

/**
 * The Identity tab: who the identity is (its profile at the home node, its public profile card) and
 * where it lives (the home node and its status). Reading goes through this app's device key; changing
 * the profile and publishing the card are acts of authority, confirmed with the user key.
 */
@HiltViewModel
class IdentityViewModel @Inject constructor(
    private val vault: IdentityVault,
    private val activeIdentity: ActiveIdentity,
    private val nodes: NodeConnections,
    private val cards: ProfileCards,
    private val logos: NodeLogos,
    private val avatars: AvatarCache,
    private val adminAccess: AdminAccess,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(IdentityUiState())
    val state: StateFlow<IdentityUiState> = _state.asStateFlow()

    private var loading: Job? = null
    private var working: Job? = null

    init {
        viewModelScope.launch {
            // The identity picked in the switcher. A change of its name alone doesn't ask the node again.
            activeIdentity.active.collect { identity ->
                val previous = _state.value.identity
                val switched = identity?.userId != previous?.userId
                val moved = identity?.homeNode != previous?.homeNode
                if (switched || moved) working?.cancel()
                _state.update {
                    if (switched || moved) {
                        IdentityUiState(identity = identity, avatar = identity?.userId?.let { id -> avatars.avatars.value[id] })
                    } else {
                        it.copy(identity = identity)
                    }
                }
                if (switched || moved) refresh()
            }
        }
        viewModelScope.launch {
            // The picture kept for the identity shows at once; the node is asked whether it is current.
            avatars.load()
            combine(activeIdentity.active, avatars.avatars) { identity, all -> identity?.userId?.let { all[it] } }
                .collect { avatar -> _state.update { it.copy(avatar = avatar) } }
        }
    }

    /** Asks the home node again; after a dismissed prompt, this is the "Unlock" action. */
    fun refresh() {
        val identity = _state.value.identity ?: return
        // An answer still on its way may be for the identity shown before.
        loading?.cancel()
        val homeNode = identity.homeNode
        if (homeNode == null) {
            _state.update { it.copy(node = NodeState.None, refreshing = false, card = CardUi()) }
            return
        }
        // What the node said last stays on screen while it is asked again.
        val quiet = _state.value.node is NodeState.Ready
        _state.update { if (quiet) it.copy(refreshing = true) else it.copy(node = NodeState.Loading) }
        loading = viewModelScope.launch {
            val node = load(identity)
            _state.update { it.copy(node = node, refreshing = false) }
            if (node is NodeState.Ready) {
                loadAvatar(identity, node.profile.avatar)
                loadLogo(homeNode, node.node.logo)
                loadCard(identity, node.node)
            }
        }
    }

    private fun unlockPrompt() = AuthPrompt(
        context.getString(R.string.id_unlock_prompt_title),
        context.getString(R.string.id_unlock_prompt_subtitle),
    )

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
            val view = ProfileView(
                name = profile.name.orElse(null)?.takeIf { it.isNotBlank() },
                bio = profile.bio.orElse(null)?.takeIf { it.isNotBlank() },
                email = profile.email.orElse(null)?.takeIf { it.isNotBlank() },
                avatar = profile.avatar.orElse(null)?.takeIf { it.isNotBlank() },
                plan = profile.planName,
                passphraseProtected = profile.isPassphraseProtected,
                admin = profile.isAdmin,
            )
            rememberName(identity, view.name)
            // An account marked as an administrator gets the Dashboard, with no prompt of its own.
            adminAccess.reportProfile(identity.userId, profile.isAdmin)
            NodeState.Ready(view, homeNodeView(status))
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

    /**
     * Shows the account's avatar. A copy already held is checked with the node, which sends the image
     * again only if it changed. A picture is a nicety: when it can't be had, the identicon stays.
     */
    private suspend fun loadAvatar(identity: IdentityInfo, uri: String?) {
        if (uri == null) {
            avatars.put(identity.userId, null)
            return
        }
        try {
            val client = nodes.deviceClient(identity, unlockPrompt())
            val held = avatars.held(identity.userId)
            val avatar = if (held == null) {
                call { client.avatar.await() }.orElse(null)
            } else {
                val refresh = call { client.refreshAvatar(held).await() }
                if (refresh.status == AvatarRefresh.Status.UNCHANGED) return
                refresh.avatar.orElse(null)
            }
            avatars.put(identity.userId, avatar)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keeps what is shown.
        }
    }

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
        val card = try {
            when (val status = cards.status(identity, unlockPrompt())) {
                CardStatus.NotPublished -> CardState.Off
                is CardStatus.Published -> CardState.On(status.version)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.ServiceUnavailable) {
            CardState.Unavailable
        } catch (e: Exception) {
            CardState.Failed(e.userMessage(context))
        }
        _state.update { it.copy(card = it.card.copy(state = card)) }
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
        val s = _state.value
        val identity = s.identity ?: return
        val ready = s.node as? NodeState.Ready ?: return
        val confirm = s.card.confirm ?: return
        val content = cardContent(identity, ready.profile, ready.node) ?: return
        _state.update { it.copy(card = it.card.copy(confirm = null, busy = true, error = null)) }
        working = viewModelScope.launch {
            try {
                val card = when (confirm) {
                    CardConfirm.PUBLISH -> {
                        val prompt = AuthPrompt(
                            context.getString(R.string.id_card_prompt_title),
                            context.getString(R.string.id_card_prompt_subtitle),
                        )
                        CardState.On(cards.publish(identity, content, prompt))
                    }
                    CardConfirm.STOP -> {
                        cards.unpublish(identity, unlockPrompt())
                        CardState.Off
                    }
                }
                _state.update { it.copy(card = CardUi(state = card)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.AuthenticationCancelled) {
                _state.update { it.copy(card = it.card.copy(busy = false)) }
            } catch (e: Exception) {
                _state.update { it.copy(card = it.card.copy(busy = false, error = e.userMessage(context))) }
            }
        }
    }

    // The card for [profile] at [node]; null while the node's id is unknown (its status unread).
    private fun cardContent(identity: IdentityInfo, profile: ProfileView, node: HomeNodeView): CardContent? {
        val nodeId = node.nodeId ?: identity.homeNode?.nodeId ?: return null
        return CardContent(
            name = profile.name,
            bio = profile.bio,
            avatar = profile.avatar,
            homeNode = nodeId,
            messagingHomePeer = node.messagingPeer,
        )
    }

    // ---- Editing the profile -------------------------------------------------------------------

    fun startEdit() {
        val s = _state.value
        val view = (s.node as? NodeState.Ready)?.profile ?: return
        _state.update {
            it.copy(
                edit = ProfileEdit(
                    view.name.orEmpty(),
                    view.bio.orEmpty(),
                    view.email.orEmpty(),
                    needsPassphrase = view.passphraseProtected,
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
     * Saves the changed fields, after the user confirms with their screen lock. When the node wants the
     * account passphrase, the dialog asks for it, and saving again sends it. A published card is
     * updated under the same prompt; if only that fails, the profile is still saved and the card says so.
     */
    fun save() {
        val s = _state.value
        val identity = s.identity ?: return
        val node = identity.homeNode ?: return
        val ready = s.node as? NodeState.Ready ?: return
        val view = ready.profile
        val edit = s.edit ?: return
        if (edit.busy) return

        val name = edit.name.trim().ifEmpty { null }
        val bio = edit.bio.trim().ifEmpty { null }
        val email = edit.email.trim().ifEmpty { null }
        if (email != null && !looksLikeEmail(email)) {
            updateEdit { it.copy(error = context.getString(R.string.id_edit_invalid_email)) }
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

        val saved = view.copy(name = name, bio = bio, email = email)
        // Only what the card shows makes it worth publishing again.
        val republish = (s.card.state as? CardState.On)?.takeIf { name != view.name || bio != view.bio }
            ?.let { cardContent(identity, saved, ready.node) }

        updateEdit { it.copy(busy = true, error = null) }
        working = viewModelScope.launch {
            try {
                val prompt = AuthPrompt(
                    context.getString(R.string.id_edit_prompt_title),
                    context.getString(R.string.id_edit_prompt_subtitle, node.host()),
                )
                var card: CardUi? = null
                if (republish == null) {
                    nodes.asUser(identity.userId, node, prompt) { client, _ ->
                        call { client.updateProfile(update, passphrase).await() }
                    }
                } else {
                    nodes.asUserWithKey(identity.userId, node, prompt) { client, userKey ->
                        call { client.updateProfile(update, passphrase).await() }
                        card = try {
                            CardUi(state = CardState.On(cards.publish(identity, republish, userKey, prompt)))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _state.value.card.copy(error = context.getString(R.string.id_card_update_failed, e.userMessage(context)))
                        }
                    }
                }
                rememberName(identity, name)
                _state.update {
                    it.copy(node = ready.copy(profile = saved), edit = null, card = card ?: it.card)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.PassphraseRequired) {
                updateEdit { it.copy(busy = false, needsPassphrase = true, passphrase = "", error = e.userMessage(context)) }
            } catch (e: AppError.Forbidden) {
                // With a passphrase given, the node refuses only a wrong one.
                val message = if (passphrase != null) context.getString(R.string.id_wrong_passphrase) else e.userMessage(context)
                updateEdit { it.copy(busy = false, passphrase = "", error = message) }
            } catch (e: AppError.AuthenticationCancelled) {
                updateEdit { it.copy(busy = false) }
            } catch (e: Exception) {
                updateEdit { it.copy(busy = false, error = e.userMessage(context)) }
            }
        }
    }

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

/** The node's QR code, in the form the node's home page shows and the app's scanner reads. */
fun nodeQrPayload(node: NodeAddress): String =
    JSONObject().put("url", node.url).apply { node.nodeId?.let { put("id", it) } }.toString()

/** The node's host name, to name it in a sentence; its URL when that has none. */
fun NodeAddress.host(): String = runCatching { URI(url).host }.getOrNull() ?: url
