package org.polyfrost.polyplus.client.network.http

import org.polyfrost.polyplus.client.dusk.DuskException
import org.polyfrost.polyplus.client.dusk.DuskGroups
import org.polyfrost.polyplus.client.dusk.DuskSocial
import org.polyfrost.polyplus.client.dusk.json
import org.polyfrost.polyplus.client.network.http.responses.GroupMessage
import org.polyfrost.polyplus.client.network.http.responses.GroupSummary
import org.polyfrost.polyplus.client.utils.runSuspendCatching

/** A conversation per friend, plus Dusk's group chats (negative ids, see [DuskGroups]). */
@Suppress("UnusedParameter")
object GroupsApi {
    suspend fun listPage(cursor: String?): Result<CursorPage<GroupSummary>> = runSuspendCatching {
        val all = DuskSocial.conversations() + DuskGroups.list()
        CursorPage(all.sortedWith(compareByDescending<GroupSummary> { it.unread }.thenByDescending { it.lastMessage?.sentAt ?: "" }))
    }

    fun delegations(): Result<List<String>> = Result.success(emptyList())

    suspend fun openDirectMessage(player: String): Result<GroupSummary> = runSuspendCatching {
        val id = DuskSocial.groupId(player)
        DuskSocial.conversations().firstOrNull { it.id == id } ?: throw DuskException("You can only message friends")
    }

    suspend fun createGroup(name: String, members: List<String>): Result<GroupSummary> =
        runSuspendCatching { DuskGroups.create(name, members) }

    suspend fun addMember(groupId: Int, player: String): Result<Unit> = runSuspendCatching {
        groupOnly(groupId)
        DuskGroups.addMember(groupId, player)
    }

    suspend fun removeMember(groupId: Int, player: String): Result<Unit> = runSuspendCatching {
        groupOnly(groupId)
        DuskGroups.removeMember(groupId, player)
    }

    /** Direct messages keep only their newest page; groups scroll back. */
    suspend fun messages(groupId: Int, before: Long? = null, limit: Long? = null): Result<List<GroupMessage>> = runSuspendCatching {
        when {
            DuskGroups.isGroup(groupId) -> DuskGroups.messages(groupId, before = before)
            before != null -> emptyList()
            else -> DuskSocial.messages(DuskSocial.playerOf(groupId))
        }
    }

    suspend fun sendMessage(groupId: Int, content: String, idempotencyKey: String? = null): Result<GroupMessage> = runSuspendCatching {
        if (DuskGroups.isGroup(groupId)) DuskGroups.send(groupId, content) else DuskSocial.send(DuskSocial.playerOf(groupId), json("body" to content))
    }

    suspend fun editMessage(groupId: Int, messageId: Long, content: String): Result<GroupMessage> = runSuspendCatching {
        groupOnly(groupId)
        DuskGroups.edit(groupId, messageId, content)
    }

    suspend fun deleteMessage(groupId: Int, messageId: Long): Result<Unit> = runSuspendCatching {
        groupOnly(groupId)
        DuskGroups.delete(groupId, messageId)
    }

    // a direct conversation is marked read by reading it
    suspend fun markRead(groupId: Int, messageId: Long): Result<Unit> = runSuspendCatching {
        if (DuskGroups.isGroup(groupId)) {
            DuskGroups.markRead(groupId, messageId)
        } else {
            DuskSocial.messages(DuskSocial.playerOf(groupId), (messageId - 1).coerceAtLeast(0))
        }
        Unit
    }

    fun claim(groupId: Int): Result<GroupSummary> = Result.failure(DuskException("Dusk doesn't have that"))

    private fun groupOnly(groupId: Int) {
        if (!DuskGroups.isGroup(groupId)) throw DuskException("That works in group chats only")
    }
}
