package org.polyfrost.polyplus.client.network.http

import org.polyfrost.polyplus.PolyPlusConstants
import org.polyfrost.polyplus.client.dusk.DuskException
import org.polyfrost.polyplus.client.dusk.DuskHosting
import org.polyfrost.polyplus.client.dusk.DuskSocial
import org.polyfrost.polyplus.client.dusk.json
import org.polyfrost.polyplus.client.network.http.responses.SessionInvite
import org.polyfrost.polyplus.client.network.http.responses.SessionResponse
import org.polyfrost.polyplus.client.social.ChatIdentity
import org.polyfrost.polyplus.client.utils.runSuspendCatching
import java.time.Instant
import java.util.UUID

/**
 * Hosted worlds. The host's own game is the server; Dusk only carries the invite, a direct message
 * with the address in it, so a session is nothing but a local id.
 */
@Suppress("UnusedParameter")
object SessionsApi {
    private const val LIFETIME_SECONDS = 24 * 60 * 60L

    fun create(eosSessionId: String? = null): Result<SessionResponse> = Result.success(session(UUID.randomUUID().toString()))

    fun updateEosSessionId(sessionId: String, eosSessionId: String): Result<SessionResponse> = Result.success(session(sessionId))

    fun close(sessionId: String): Result<Unit> = Result.success(Unit)

    suspend fun invite(sessionId: String, player: String): Result<SessionInvite> = runSuspendCatching {
        val address = DuskHosting.awaitAddress() ?: throw DuskException(DuskHosting.NO_ADDRESS)
        val version = PolyPlusConstants.MINECRAFT_VERSION
        val sent = DuskSocial.send(
            DuskSocial.dashed(player),
            json("kind" to "invite", "meta" to json("server" to address, "version" to version)),
        )
        SessionInvite(
            id = sent.id.toInt(),
            sessionId = sessionId,
            sender = ChatIdentity.localId(),
            createdAt = sent.sentAt,
            minecraftVersion = version,
            server = address,
        )
    }

    fun incomingInvites(): Result<List<SessionInvite>> = Result.success(DuskSocial.pendingInvites())

    fun acceptInvite(inviteId: Int): Result<Unit> = answer(inviteId, "accepted")

    fun declineInvite(inviteId: Int): Result<Unit> = answer(inviteId, "declined")

    private fun answer(inviteId: Int, status: String): Result<Unit> =
        if (DuskSocial.answerInvite(inviteId, status) != null) Result.success(Unit) else Result.failure(DuskException("That invite is gone"))

    private fun session(id: String) = SessionResponse(id = id, expiresAt = Instant.now().plusSeconds(LIFETIME_SECONDS).toString())
}
