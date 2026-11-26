package org.polyfrost.polyplus.client.network.http

import org.polyfrost.polyplus.client.dusk.DuskException
import org.polyfrost.polyplus.client.dusk.DuskSocial
import org.polyfrost.polyplus.client.dusk.json
import org.polyfrost.polyplus.client.network.http.responses.GroupMessage
import org.polyfrost.polyplus.client.network.http.responses.GroupSummary
import org.polyfrost.polyplus.client.utils.runSuspendCatching

/** Dusk has direct messages only: a conversation per friend. */
@Suppress("UnusedParameter")
object GroupsApi {
    suspend fun listPage(cursor: String?): Result<CursorPage<GroupSummary>> = runSuspendCatching { CursorPage(DuskSocial.conversations()) }

    fun delegations(): Result<List<String>> = Result.success(emptyList())

    suspend fun openDirectMessage(player: String): Result<GroupSummary> = runSuspendCatching {
        val id = DuskSocial.groupId(player)
        DuskSocial.conversations().firstOrNull { it.id == id } ?: throw DuskException("You can only message friends")
    }

    fun createGroup(name: String, members: List<String>): Result<GroupSummary> = unsupported()

    fun addMember(groupId: Int, player: String): Result<Unit> = unsupported()

    fun removeMember(groupId: Int, player: String): Result<Unit> = unsupported()

    /** The newest page; the service keeps no older one to ask for. */
    suspend fun messages(groupId: Int, before: Long? = null, limit: Long? = null): Result<List<GroupMessage>> = runSuspendCatching {
        if (before != null) emptyList() else DuskSocial.messages(DuskSocial.playerOf(groupId))
    }

    suspend fun sendMessage(groupId: Int, content: String, idempotencyKey: String? = null): Result<GroupMessage> =
        runSuspendCatching { DuskSocial.send(DuskSocial.playerOf(groupId), json("body" to content)) }

    fun editMessage(groupId: Int, messageId: Long, content: String): Result<GroupMessage> = unsupported()

    fun deleteMessage(groupId: Int, messageId: Long): Result<Unit> = unsupported()

    // reading a conversation is what marks it read
    suspend fun markRead(groupId: Int, messageId: Long): Result<Unit> = runSuspendCatching {
        DuskSocial.messages(DuskSocial.playerOf(groupId), (messageId - 1).coerceAtLeast(0))
        Unit
    }

    fun claim(groupId: Int): Result<GroupSummary> = unsupported()

    private fun <T> unsupported(): Result<T> = Result.failure(DuskException("Dusk doesn't have group chats"))
}
