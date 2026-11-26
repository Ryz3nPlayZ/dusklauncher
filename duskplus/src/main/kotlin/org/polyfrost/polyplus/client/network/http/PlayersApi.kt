package org.polyfrost.polyplus.client.network.http

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.polyfrost.polyplus.client.dusk.DuskSocial
import org.polyfrost.polyplus.client.dusk.str
import org.polyfrost.polyplus.client.utils.runSuspendCatching
import java.net.HttpURLConnection
import java.net.URI

/** Names by uuid: whoever Dusk has told us about, then Mojang's own list. */
object PlayersApi {
    private const val PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/"
    private const val TIMEOUT_MS = 8_000
    private const val HTTP_OK = 200

    suspend fun resolve(ids: List<String>): Result<Map<String, String>> = runSuspendCatching {
        withContext(Dispatchers.IO) {
            ids.mapNotNull { id ->
                val uuid = DuskSocial.dashed(id)
                val name = DuskSocial.names[uuid] ?: lookUp(uuid)?.also { DuskSocial.names[uuid] = it }
                name?.let { id to it }
            }.toMap()
        }
    }

    private fun lookUp(uuid: String): String? = runCatching {
        val connection = URI(PROFILE + uuid.replace("-", "")).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        try {
            if (connection.responseCode != HTTP_OK) return@runCatching null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            @Suppress("DEPRECATION") // the static form isn't in 1.8.9's gson
            (JsonParser().parse(body) as? JsonObject)?.str("name")
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
