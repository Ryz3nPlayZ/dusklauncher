package org.polyfrost.polyplus.client.network.http

import kotlinx.serialization.Serializable
import org.polyfrost.polyplus.PolyPlusConstants
import org.polyfrost.polyplus.client.PolyPlusClient
import org.polyfrost.polyplus.client.dusk.DuskBridge
import org.polyfrost.polyplus.client.dusk.DuskHosting
import org.polyfrost.polyplus.client.network.http.responses.ApiErrorBody
import org.polyfrost.polyplus.libs.ktor.client.call.body
import org.polyfrost.polyplus.libs.ktor.client.request.HttpRequestBuilder
import org.polyfrost.polyplus.libs.ktor.client.request.bearerAuth
import org.polyfrost.polyplus.libs.ktor.client.request.request
import org.polyfrost.polyplus.libs.ktor.client.statement.bodyAsText
import org.polyfrost.polyplus.libs.ktor.client.request.setBody
import org.polyfrost.polyplus.libs.ktor.client.statement.HttpResponse
import org.polyfrost.polyplus.libs.ktor.http.ContentType
import org.polyfrost.polyplus.libs.ktor.http.HttpMethod
import org.polyfrost.polyplus.libs.ktor.http.HttpStatusCode
import org.polyfrost.polyplus.libs.ktor.http.contentType
import org.polyfrost.polyplus.client.network.http.responses.HostedJoinRequest
import org.polyfrost.polyplus.client.network.http.responses.Party
import org.polyfrost.polyplus.client.network.http.responses.PartyAcceptRequest
import org.polyfrost.polyplus.client.network.http.responses.PartyHardMuteRequest
import org.polyfrost.polyplus.client.network.http.responses.PartyInvite
import org.polyfrost.polyplus.client.network.http.responses.PartyInvitePolicy
import org.polyfrost.polyplus.client.network.http.responses.PartyPlayerRequest
import org.polyfrost.polyplus.client.network.http.responses.PartySettingsRequest
import org.polyfrost.polyplus.client.network.http.responses.PartyVoiceMode
import org.polyfrost.polyplus.client.network.http.responses.SessionInvite
import org.polyfrost.polyplus.client.network.http.responses.VoicePass
import org.polyfrost.polyplus.client.network.http.responses.VoiceToken
import org.polyfrost.polyplus.client.utils.runSuspendCatching

class PartyApiException(val status: Int, val code: String?, message: String?, cause: Throwable? = null) : RuntimeException(message ?: code ?: "HTTP $status", cause)

/** Where a hosted world can be reached, sent along when the host lets people in. */
@Serializable
internal data class HostedWorld(val server: String, val version: String)

object PartyApi {
    /** One call to Dusk's `/v1/party` routes as the signed-in player; a refusal becomes a [PartyApiException]. */
    private suspend fun send(path: String, method: HttpMethod, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val base = DuskBridge.apiBase() ?: throw PartyApiException(503, "unavailable", "Dusk Client isn't installed")
        var token = DuskBridge.bearer()
        for (attempt in 0..1) {
            val response = PolyPlusClient.HTTP.request("$base/v1/party$path") {
                this.method = method
                bearerAuth(token)
                block()
            }
            if (response.status == HttpStatusCode.Unauthorized && attempt == 0) {
                token = DuskBridge.bearer(fresh = true)
                continue
            }
            if (response.status.value >= 400) {
                val error = runCatching { PolyPlusClient.JSON.decodeFromString(ApiErrorBody.serializer(), response.bodyAsText()) }.getOrNull()
                throw PartyApiException(response.status.value, error?.error, error?.message)
            }
            return response
        }
        error("unreachable")
    }

    /** The open world's address, for the people let into it; null when it has none (no e4mc). */
    private suspend fun hostedWorld(): HostedWorld? =
        DuskHosting.awaitAddress()?.let { HostedWorld(it, PolyPlusConstants.MINECRAFT_VERSION) }

    private suspend inline fun <reified T> call(path: String, method: HttpMethod, noinline block: HttpRequestBuilder.() -> Unit = {}): Result<T> =
        runSuspendCatching { send(path, method, block).body<T>() }

    private suspend fun unit(path: String, method: HttpMethod, block: HttpRequestBuilder.() -> Unit = {}): Result<Unit> =
        runSuspendCatching { send(path, method, block) }.map { }

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    suspend fun current(): Result<Party?> = runSuspendCatching {
        val response = send("", HttpMethod.Get)
        if (response.status == HttpStatusCode.NoContent) null else response.body<Party>()
    }

    suspend fun create(voiceMode: PartyVoiceMode? = null, invitePolicy: PartyInvitePolicy? = null): Result<Party> =
        call("", HttpMethod.Post) { json(PartySettingsRequest(voiceMode, invitePolicy)) }

    suspend fun updateSettings(voiceMode: PartyVoiceMode? = null, invitePolicy: PartyInvitePolicy? = null): Result<Party> =
        call("/settings", HttpMethod.Put) { json(PartySettingsRequest(voiceMode, invitePolicy)) }

    suspend fun leave(): Result<Unit> = unit("/leave", HttpMethod.Post)

    suspend fun disband(): Result<Unit> = unit("", HttpMethod.Delete)

    suspend fun transferLeader(player: String): Result<Party> =
        call("/leader", HttpMethod.Post) { json(PartyPlayerRequest(player)) }

    suspend fun kick(player: String): Result<Unit> = unit("/members/$player", HttpMethod.Delete)

    suspend fun setHardMuted(player: String, hardMuted: Boolean): Result<Unit> =
        unit("/members/$player/voice", HttpMethod.Put) { json(PartyHardMuteRequest(hardMuted)) }

    suspend fun invite(player: String): Result<PartyInvite> =
        call("/invites", HttpMethod.Post) { json(PartyPlayerRequest(player)) }

    suspend fun incomingInvites(): Result<List<PartyInvite>> = call("/invites/incoming", HttpMethod.Get)

    suspend fun outgoingInvites(): Result<List<PartyInvite>> = call("/invites/outgoing", HttpMethod.Get)

    suspend fun acceptInvite(id: String, leaveCurrent: Boolean): Result<Party> =
        call("/invites/$id/accept", HttpMethod.Post) { json(PartyAcceptRequest(leaveCurrent)) }

    suspend fun declineInvite(id: String): Result<Unit> = unit("/invites/$id/decline", HttpMethod.Post)

    suspend fun cancelInvite(id: String): Result<Unit> = unit("/invites/$id", HttpMethod.Delete)

    suspend fun voiceToken(): Result<VoiceToken> = call("/voice/token", HttpMethod.Post)

    suspend fun voicePass(): Result<VoicePass> = call("/voice/pass", HttpMethod.Post)

    suspend fun inviteSession(sessionId: String): Result<List<SessionInvite>> {
        val world = hostedWorld() ?: return Result.failure(PartyApiException(409, "no_address", DuskHosting.NO_ADDRESS))
        return call("/sessions/$sessionId/invite", HttpMethod.Post) { json(world) }
    }

    /** The host closed their world; anyone still asking to join it is told so. */
    suspend fun closeSession(sessionId: String): Result<Unit> = unit("/sessions/$sessionId", HttpMethod.Delete)

    suspend fun requestHostedJoin(sessionId: String): Result<HostedJoinRequest> = call("/sessions/$sessionId/join-requests", HttpMethod.Post)

    suspend fun allowHostedJoin(requestId: String): Result<Unit> {
        val world = hostedWorld()
        return unit("/join-requests/$requestId/allow", HttpMethod.Post) { json(world ?: emptyMap<String, String>()) }
    }

    suspend fun denyHostedJoin(requestId: String): Result<Unit> = unit("/join-requests/$requestId/deny", HttpMethod.Post)
}
