package org.polyfrost.polyplus.client.dusk

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.polyfrost.polyplus.client.network.http.responses.GroupKind
import org.polyfrost.polyplus.client.network.http.responses.GroupLastMessage
import org.polyfrost.polyplus.client.network.http.responses.GroupMessage
import org.polyfrost.polyplus.client.network.http.responses.GroupSummary
import org.polyfrost.polyplus.client.network.websocket.ClientboundPacket
import org.polyfrost.polyplus.client.social.GroupsRepository
import java.time.Instant

/**
 * Dusk's group chats (`/v1/groups`) in the shapes the social screens expect. A group's id here is
 * its server id negated, so it never meets a direct conversation's id (those are never negative).
 */
object DuskGroups {
    fun isGroup(id: Int): Boolean = id < 0

    private fun serverId(id: Int): Long = -id.toLong()

    private fun time(seconds: Long): String = Instant.ofEpochSecond(seconds).toString()

    /** The newest message id and unread count each group had at the last poll. */
    private var seen: Map<Int, Pair<Long, Long>>? = null

    private fun summary(o: JsonObject): GroupSummary {
        val members = o.list("members").map(DuskSocial::remember)
        val last = o.get("lastMessage") as? JsonObject
        return GroupSummary(
            id = -o.long("id").toInt(),
            kind = GroupKind.Group,
            name = o.str("name"),
            members = members,
            lastMessage = last?.let { GroupLastMessage(it.str("body").orEmpty(), DuskSocial.dashed(it.str("fromUuid").orEmpty()), time(it.long("sentAt"))) },
            unread = o.long("unread") > 0,
        )
    }

    private fun message(o: JsonObject): GroupMessage = GroupMessage(
        id = o.long("id"),
        sender = DuskSocial.dashed(o.str("fromUuid").orEmpty()),
        content = o.str("body").orEmpty(),
        sentAt = time(o.long("sentAt")),
        editedAt = o.long("editedAt").takeIf { it > 0 }?.let(::time),
    )

    suspend fun list(): List<GroupSummary> = DuskBridge.array("GET", "/v1/groups").objects().map(::summary)

    suspend fun create(name: String, members: List<String>): GroupSummary {
        val ids = JsonArray().apply { members.forEach { add(DuskSocial.dashed(it)) } }
        return summary(DuskBridge.obj("POST", "/v1/groups", json("name" to name, "members" to ids)))
    }

    suspend fun addMember(id: Int, player: String) {
        DuskBridge.obj("POST", "/v1/groups/${serverId(id)}/members", json("uuid" to DuskSocial.dashed(player)))
    }

    suspend fun removeMember(id: Int, player: String) {
        DuskBridge.call("DELETE", "/v1/groups/${serverId(id)}/members/${DuskSocial.dashed(player)}")
    }

    /** Oldest-first; reading marks it read. [before] scrolls back, [after] polls for what's new. */
    suspend fun messages(id: Int, before: Long? = null, after: Long? = null): List<GroupMessage> {
        val query = when {
            after != null -> "?afterId=$after"
            before != null -> "?beforeId=$before"
            else -> ""
        }
        return DuskBridge.array("GET", "/v1/groups/${serverId(id)}/messages$query").objects().map(::message)
    }

    suspend fun send(id: Int, content: String): GroupMessage =
        message(DuskBridge.obj("POST", "/v1/groups/${serverId(id)}/messages", json("body" to content)))

    suspend fun edit(id: Int, messageId: Long, content: String): GroupMessage =
        message(DuskBridge.obj("PATCH", "/v1/groups/${serverId(id)}/messages/$messageId", json("body" to content)))

    suspend fun delete(id: Int, messageId: Long) {
        DuskBridge.call("DELETE", "/v1/groups/${serverId(id)}/messages/$messageId")
    }

    suspend fun markRead(id: Int, messageId: Long) {
        DuskBridge.call("POST", "/v1/groups/${serverId(id)}/read", json("lastId" to messageId))
    }

    /**
     * One look at the groups: new lines of an open group are handed on as they'd arrive over a
     * socket. True when the list changed (a new group, a new line, one left), so it's worth syncing.
     */
    suspend fun poll(post: (ClientboundPacket) -> Unit): Boolean {
        val raw = DuskBridge.array("GET", "/v1/groups").objects()
        val now = raw.associate { -it.long("id").toInt() to (((it.get("lastMessage") as? JsonObject)?.long("id") ?: 0L) to it.long("unread")) }
        val before = seen
        seen = now
        if (before == null) return false
        for ((id, state) in now) {
            if (state.first != before[id]?.first && GroupsRepository.isOpen(id)) readNew(id, post)
        }
        return now != before
    }

    /** New lines of the open groups, between full polls. */
    suspend fun watch(post: (ClientboundPacket) -> Unit) {
        seen?.keys?.filter(GroupsRepository::isOpen)?.forEach { readNew(it, post) }
    }

    private suspend fun readNew(id: Int, post: (ClientboundPacket) -> Unit) {
        val newest = GroupsRepository.newestMessage(id)?.id ?: return
        messages(id, after = newest).forEach {
            post(ClientboundPacket.GroupMessageReceived(groupId = id, messageId = it.id, sender = it.sender, content = it.content))
        }
    }

    /** Another account signed in. */
    fun reset() {
        seen = null
    }
}
