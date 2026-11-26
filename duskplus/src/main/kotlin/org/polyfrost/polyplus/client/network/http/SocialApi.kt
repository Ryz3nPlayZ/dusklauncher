package org.polyfrost.polyplus.client.network.http

import org.polyfrost.polyplus.client.dusk.DuskBridge
import org.polyfrost.polyplus.client.dusk.DuskSocial
import org.polyfrost.polyplus.client.dusk.json
import org.polyfrost.polyplus.client.dusk.objects
import org.polyfrost.polyplus.client.network.http.responses.BlockedPlayer
import org.polyfrost.polyplus.client.network.http.responses.Friend
import org.polyfrost.polyplus.client.network.http.responses.FriendRequest
import org.polyfrost.polyplus.client.utils.runSuspendCatching

/** Dusk answers with whole lists, so every page is the only one. */
@Suppress("UnusedParameter")
object SocialApi {
    suspend fun friendsPage(cursor: String?): Result<CursorPage<Friend>> = runSuspendCatching { CursorPage(DuskSocial.friends()) }

    suspend fun removeFriend(player: String): Result<Unit> = runSuspendCatching {
        DuskBridge.call("DELETE", "/v1/friends/$player")
        Unit
    }

    suspend fun incomingRequestsPage(cursor: String?): Result<CursorPage<FriendRequest>> =
        runSuspendCatching { CursorPage(DuskSocial.requests().incoming) }

    suspend fun outgoingRequestsPage(cursor: String?): Result<CursorPage<FriendRequest>> =
        runSuspendCatching { CursorPage(DuskSocial.requests().outgoing) }

    /** [player] is a uuid or a username. The request now waiting, or null when it made you friends. */
    suspend fun sendRequest(player: String): Result<FriendRequest?> = runSuspendCatching {
        val uuid = player.takeIf { it.replace("-", "").length == UUID_LENGTH }?.let(DuskSocial::dashed)
        val body = if (uuid != null) json("uuid" to uuid) else json("username" to player)
        DuskSocial.requests(DuskBridge.obj("POST", "/v1/friends/requests", body)).outgoing.firstOrNull {
            if (uuid != null) it.player == uuid else DuskSocial.names[it.player].equals(player, ignoreCase = true)
        }
    }

    suspend fun acceptRequest(id: Int): Result<Unit> = answer(id, "accept")

    suspend fun declineRequest(id: Int): Result<Unit> = answer(id, "decline")

    // the same route: either side may end a request
    suspend fun cancelRequest(id: Int): Result<Unit> = answer(id, "decline")

    suspend fun blockedPage(cursor: String?): Result<CursorPage<BlockedPlayer>> = runSuspendCatching {
        CursorPage(DuskBridge.array("GET", "/v1/blocks").objects().map { BlockedPlayer(DuskSocial.remember(it), "1970-01-01T00:00:00Z") })
    }

    suspend fun block(player: String): Result<Unit> = runSuspendCatching {
        DuskBridge.call("POST", "/v1/blocks", json("uuid" to player))
        Unit
    }

    suspend fun unblock(player: String): Result<Unit> = runSuspendCatching {
        DuskBridge.call("DELETE", "/v1/blocks/$player")
        Unit
    }

    private suspend fun answer(id: Int, what: String): Result<Unit> = runSuspendCatching {
        DuskBridge.call("POST", "/v1/friends/requests/$id/$what")
        Unit
    }

    private const val UUID_LENGTH = 32
}
