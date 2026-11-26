package org.polyfrost.polyplus.client.dusk

import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.polyfrost.polyplus.client.network.http.responses.Friend
import org.polyfrost.polyplus.client.network.http.responses.FriendRequest
import org.polyfrost.polyplus.client.network.http.responses.GroupKind
import org.polyfrost.polyplus.client.network.http.responses.GroupLastMessage
import org.polyfrost.polyplus.client.network.http.responses.GroupMessage
import org.polyfrost.polyplus.client.network.http.responses.GroupMessageSessionInvite
import org.polyfrost.polyplus.client.network.http.responses.GroupSummary
import org.polyfrost.polyplus.client.network.http.responses.PlayerStatus
import org.polyfrost.polyplus.client.network.http.responses.RelationshipKind
import org.polyfrost.polyplus.client.network.http.responses.SessionInvite
import org.polyfrost.polyplus.client.social.ChatIdentity
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Dusk's friends, requests and direct messages in the shapes the social screens expect. */
object DuskSocial {
    private const val INVITE_LIFETIME_SECONDS = 2 * 60 * 60L

    data class Requests(val incoming: List<FriendRequest>, val outgoing: List<FriendRequest>)

    class FriendState(val uuid: String, val online: Boolean, val unread: Int)

    /** Everyone seen so far, by dashed uuid. */
    val names = ConcurrentHashMap<String, String>()

    private val players = ConcurrentHashMap<Int, String>()
    private val groups = ConcurrentHashMap<String, Int>()
    private val lastMessages = ConcurrentHashMap<String, GroupLastMessage>()
    private val invites = ConcurrentHashMap<Int, SessionInvite>()

    fun dashed(uuid: String): String {
        val s = uuid.replace("-", "").lowercase()
        if (s.length != 32) return uuid
        return "${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}"
    }

    /** A conversation's id: the same for a friend every time, so hidden conversations stay hidden. */
    @Synchronized
    fun groupId(player: String): Int {
        val uuid = dashed(player)
        groups[uuid]?.let { return it }
        var id = uuid.hashCode() and Int.MAX_VALUE
        while (id == 0 || players[id] != null) id = (id + 1) and Int.MAX_VALUE
        players[id] = uuid
        groups[uuid] = id
        return id
    }

    fun playerOf(groupId: Int): String = players[groupId] ?: throw DuskException("That conversation is gone")

    fun remember(player: JsonObject): String {
        val uuid = dashed(player.str("uuid") ?: throw DuskException("Dusk sent something unexpected"))
        player.str("username")?.let { names[uuid] = it }
        return uuid
    }

    private fun time(seconds: Long): String = Instant.ofEpochSecond(seconds).toString()

    // the list is asked for by several repositories at once; one request answers them all
    private val friendsLock = Mutex()
    private var friendsInFlight: CompletableDeferred<List<JsonObject>>? = null

    suspend fun friendEntries(): List<JsonObject> {
        val (mine, shared) = friendsLock.withLock {
            friendsInFlight?.let { false to it }
                ?: (true to CompletableDeferred<List<JsonObject>>().also { friendsInFlight = it })
        }
        if (!mine) return shared.await()
        try {
            val list = DuskBridge.array("GET", "/v1/friends").objects().onEach(::remember)
            shared.complete(list)
            return list
        } catch (e: Throwable) {
            shared.completeExceptionally(if (e is DuskException) e else DuskException("Couldn't reach Dusk", e))
            throw e
        } finally {
            friendsInFlight = null
        }
    }

    suspend fun friends(): List<Friend> = friendEntries().map {
        val online = it.bool("online")
        Friend(remember(it), RelationshipKind.Friend, time(it.long("lastSeen")), online, if (online) PlayerStatus.Online else PlayerStatus.Offline)
    }

    suspend fun states(): List<FriendState> = friendEntries().map { FriendState(remember(it), it.bool("online"), it.long("unread").toInt()) }

    suspend fun requests(): Requests = requests(DuskBridge.obj("GET", "/v1/friends/requests"))

    fun requests(o: JsonObject): Requests {
        fun side(key: String) = o.list(key).map { FriendRequest(it.long("id").toInt(), remember(it), time(it.long("createdAt"))) }
        return Requests(side("incoming"), side("outgoing"))
    }

    suspend fun conversations(): List<GroupSummary> = friendEntries().map { f ->
        val uuid = remember(f)
        GroupSummary(
            id = groupId(uuid),
            kind = GroupKind.Dm,
            members = listOf(ChatIdentity.localId(), uuid),
            lastMessage = lastMessages[uuid],
            unread = f.long("unread") > 0,
        )
    }.sortedWith(compareByDescending<GroupSummary> { it.unread }.thenByDescending { it.lastMessage?.sentAt ?: "" })

    /** Reads the conversation, which also marks it read (here and in the launcher). */
    suspend fun messages(player: String, afterId: Long = 0): List<GroupMessage> =
        DuskBridge.array("GET", "/v1/messages/$player?afterId=$afterId").objects().map { message(player, it) }

    suspend fun send(player: String, body: JsonObject): GroupMessage =
        message(player, DuskBridge.obj("POST", "/v1/messages/$player", body))

    private fun message(player: String, o: JsonObject): GroupMessage {
        val sender = dashed(o.str("fromUuid").orEmpty())
        val sentAt = time(o.long("sentAt"))
        val content = o.str("body").orEmpty()
        val id = o.long("id")
        val invite = invite(o, id, sender, sentAt)
        val message = GroupMessage(
            id = id,
            sender = sender,
            content = content,
            sentAt = sentAt,
            sessionInvite = invite?.let { GroupMessageSessionInvite(it.id, it.sessionId, it.status) },
        )
        val uuid = dashed(player)
        val last = lastMessages[uuid]
        if (last == null || last.sentAt <= sentAt) lastMessages[uuid] = GroupLastMessage(content, sender, sentAt)
        return message
    }

    private fun invite(o: JsonObject, id: Long, sender: String, sentAt: String): SessionInvite? {
        if (o.str("kind") != "invite") return null
        val meta = o.get("meta") as? JsonObject ?: return null
        val key = id.toInt()
        return invites.compute(key) { _, old ->
            val expired = Instant.now().epochSecond - o.long("sentAt") > INVITE_LIFETIME_SECONDS
            old?.let { if (expired && it.status == "pending") it.copy(status = "expired") else it } ?: SessionInvite(
                id = key,
                sessionId = UUID.nameUUIDFromBytes("dusk-invite:$id".toByteArray()).toString(),
                sender = sender,
                createdAt = sentAt,
                status = if (expired) "expired" else "pending",
                minecraftVersion = meta.str("version"),
                server = meta.str("server"),
            )
        }
    }

    fun pendingInvites(): List<SessionInvite> {
        val oldest = Instant.now().minusSeconds(INVITE_LIFETIME_SECONDS).toString()
        return invites.values.filter { it.status == "pending" && it.createdAt > oldest && it.sender != ChatIdentity.localId() }
    }

    fun answerInvite(id: Int, status: String): SessionInvite? = invites.computeIfPresent(id) { _, invite -> invite.copy(status = status) }

    /** Another account signed in. */
    fun reset() {
        lastMessages.clear()
        invites.clear()
    }
}
