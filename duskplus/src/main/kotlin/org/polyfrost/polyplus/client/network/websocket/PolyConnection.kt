package org.polyfrost.polyplus.client.network.websocket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.LogManager
import org.polyfrost.oneconfig.api.event.v1.EventManager
import org.polyfrost.oneconfig.api.notifications.v1.Notifications
import org.polyfrost.polyplus.client.PolyPlusClient
import org.polyfrost.polyplus.client.cosmetics.outfits.SkinLibrary
import org.polyfrost.polyplus.client.dusk.DuskBridge
import org.polyfrost.polyplus.client.dusk.DuskException
import org.polyfrost.polyplus.client.dusk.DuskSocial
import org.polyfrost.polyplus.client.network.http.responses.PlayerStatus
import org.polyfrost.polyplus.client.network.reconnectDelay
import org.polyfrost.polyplus.client.social.GroupsRepository
import org.polyfrost.polyplus.events.WebSocketMessage
import org.polyfrost.polyplus.privacy.PrivacyConsent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What's new on Dusk, asked for every few seconds and handed on as the packets the rest of the mod
 * already listens for. Dusk has no push channel, so nothing can be sent this way.
 */
object PolyConnection {
    private val LOGGER = LogManager.getLogger()

    private const val POLL_MS = 15_000L

    // a conversation on screen shouldn't wait that long for its answer
    private const val OPEN_POLL_MS = 3_000L

    private const val NOTIFY_AFTER_OUTAGE_MS = 15_000L

    private val closeListeners = CopyOnWriteArrayList<() -> Unit>()

    private var connectionCallback: (() -> Unit)? = null

    @Volatile
    private var job: Job? = null

    @Volatile
    private var connected = false

    @Volatile
    private var disconnectNotified = false

    @Volatile
    private var disconnectedSinceMs = 0L

    private var friends: Map<String, DuskSocial.FriendState>? = null
    private var requests: DuskSocial.Requests? = null

    val isConnected: Boolean
        get() = connected

    fun initialize(callback: (() -> Unit)? = null) {
        this.connectionCallback = callback
        start()
    }

    fun applyConsent() {
        if (PrivacyConsent.allowsOnlineServices()) {
            if (job == null) start()
        } else {
            close()
        }
    }

    fun reconnect() {
        close()
        start()
    }

    fun onClosed(listener: () -> Unit) {
        closeListeners.add(listener)
    }

    fun close() {
        disconnectNotified = false
        disconnectedSinceMs = 0L
        val wasRunning = job != null
        synchronized(this) {
            job?.cancel()
            job = null
            connected = false
        }
        if (wasRunning) {
            closeListeners.forEach { listener ->
                runCatching(listener).onFailure { LOGGER.error("Close listener failed", it) }
            }
        }
    }

    /** Parties, pings and worn cosmetics had packets of their own; Dusk has nowhere to send them. */
    @Suppress("UnusedParameter")
    fun sendPacket(packet: ServerboundPacket): Result<Unit> = Result.failure(DuskException("Dusk doesn't have this yet"))

    private fun start() {
        if (!PrivacyConsent.allowsOnlineServices()) return
        synchronized(this) {
            job?.cancel()
            connected = false
            friends = null
            requests = null
            job = PolyPlusClient.SCOPE.launch { loop() }
        }
    }

    private suspend fun loop() {
        val self = currentCoroutineContext()[Job]
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                poll()
                attempt = 0
                if (!connected) {
                    synchronized(this) { if (job === self) connected = true }
                    onConnected()
                }
                watch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(this) { if (job === self) connected = false }
                LOGGER.debug("Dusk didn't answer: {}", e.message)
                // no account to sign in as isn't an outage
                if (DuskBridge.available) notifyDisconnected()
                delay(reconnectDelay(++attempt))
            }
        }
    }

    /** Waits for the next full poll, keeping whatever conversation is open current meanwhile. */
    private suspend fun watch() {
        var waited = 0L
        while (waited < POLL_MS) {
            delay(OPEN_POLL_MS)
            waited += OPEN_POLL_MS
            friends?.keys?.filter { GroupsRepository.isOpen(DuskSocial.groupId(it)) }?.forEach { readNew(it) }
        }
    }

    private fun onConnected() {
        disconnectedSinceMs = 0L
        runCatching { SkinLibrary.onSignedIn(Minecraft.getInstance().user.profileId) }
            .onFailure { LOGGER.error("Failed to load the skin library", it) }
        connectionCallback?.invoke()
        if (!disconnectNotified) return
        disconnectNotified = false
        runCatching { Notifications.success("Dusk", "Reconnected to Dusk.") }
    }

    private suspend fun poll() {
        val states = DuskSocial.states().associateBy { it.uuid }
        val pending = DuskSocial.requests()
        val oldFriends = friends
        val oldRequests = requests
        friends = states
        requests = pending
        if (oldFriends == null || oldRequests == null) return
        presenceChanges(oldFriends, states)
        requestChanges(oldRequests, pending, states)

        val unread = states.values.filter { it.unread > (oldFriends[it.uuid]?.unread ?: 0) }
        for (friend in unread.filter { GroupsRepository.isOpen(DuskSocial.groupId(it.uuid)) }) readNew(friend.uuid)
        if (unread.isNotEmpty() || states.keys != oldFriends.keys) GroupsRepository.syncGroups()
    }

    private fun presenceChanges(old: Map<String, DuskSocial.FriendState>, new: Map<String, DuskSocial.FriendState>) {
        for (friend in new.values) {
            val before = old[friend.uuid] ?: continue
            if (before.online == friend.online) continue
            post(ClientboundPacket.PlayerPresence(friend.uuid, friend.online, if (friend.online) PlayerStatus.Online else PlayerStatus.Offline))
        }
        (old.keys - new.keys).forEach { post(ClientboundPacket.FriendRemoved(it)) }
    }

    private fun requestChanges(old: DuskSocial.Requests, new: DuskSocial.Requests, friends: Map<String, DuskSocial.FriendState>) {
        val known = old.incoming.map { it.id }.toSet()
        new.incoming.filter { it.id !in known }.forEach { post(ClientboundPacket.FriendRequestReceived(it.id, it.player)) }
        val left = (new.incoming + new.outgoing).map { it.id }.toSet()
        (old.incoming + old.outgoing).filter { it.id !in left }.forEach {
            post(ClientboundPacket.FriendRequestUpdated(it.id, if (it.player in friends) "accepted" else "declined"))
        }
    }

    /** New messages of a conversation the player is looking at; fetching them marks them read. */
    private suspend fun readNew(player: String) {
        val group = DuskSocial.groupId(player)
        val newest = GroupsRepository.newestMessage(group)?.id ?: return
        DuskSocial.messages(player, newest).forEach { message ->
            post(
                ClientboundPacket.GroupMessageReceived(
                    groupId = group,
                    messageId = message.id,
                    sender = message.sender,
                    content = message.content,
                    sessionInviteId = message.sessionInvite?.id,
                    sessionInviteStatus = message.sessionInvite?.status,
                ),
            )
            val invite = message.sessionInvite
            if (invite != null && invite.status == "pending" && message.sender == player) {
                post(ClientboundPacket.SessionInviteReceived(invite.id, invite.sessionId, message.sender))
            }
        }
    }

    private fun post(packet: ClientboundPacket) {
        try {
            EventManager.INSTANCE.post(WebSocketMessage(packet))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOGGER.error("Failed to handle {}", packet, e)
        }
    }

    private fun notifyDisconnected() {
        if (disconnectNotified) return
        val now = System.currentTimeMillis()
        if (disconnectedSinceMs == 0L) disconnectedSinceMs = now
        if (!outageIsWorthNotifying(disconnectedSinceMs, now)) return
        disconnectNotified = true
        runCatching { Notifications.error("Dusk", "Lost connection to Dusk. Reconnecting...") }
            .onFailure { LOGGER.error("Failed to show disconnect notification", it) }
    }

    internal fun outageIsWorthNotifying(since: Long, now: Long): Boolean = now - since >= NOTIFY_AFTER_OUTAGE_MS
}
