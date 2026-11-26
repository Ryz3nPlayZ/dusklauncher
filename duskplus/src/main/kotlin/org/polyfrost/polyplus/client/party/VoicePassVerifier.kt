package org.polyfrost.polyplus.client.party

import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.polyfrost.polyplus.client.PolyPlusClient

/** Dusk: nobody issues party passes, so none verify. */
internal object VoicePassVerifier {
    private val decoder = Base64.getUrlDecoder()

    suspend fun verify(pass: String, partyId: String, remotePuid: String): Boolean = false

    fun expiresAt(pass: String): Long? = runCatching {
        val claims = PolyPlusClient.JSON.decodeFromString(JsonObject.serializer(), String(decoder.decode(pass.split('.')[1])))
        claims["exp"]?.jsonPrimitive?.longOrNull?.times(1000L)
    }.getOrNull()
}
