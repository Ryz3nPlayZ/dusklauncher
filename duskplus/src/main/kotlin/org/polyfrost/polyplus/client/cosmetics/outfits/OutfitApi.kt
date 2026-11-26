package org.polyfrost.polyplus.client.cosmetics.outfits

import kotlinx.serialization.json.JsonObject
import org.polyfrost.polyplus.client.cosmetics.MojangSkinException
import org.polyfrost.polyplus.client.utils.runSuspendCatching
import org.polyfrost.polyplus.libs.ktor.http.HttpMethod

internal object OutfitApi {
    const val PRIVACY_MESSAGE = "Outfits aren't available."
    private const val FAILED_MESSAGE = "Couldn't save that. Check that your disk isn't full."

    fun allowed(): Boolean = true

    fun <T> privacyFailure(): Result<T> = Result.failure(OutfitException(PRIVACY_MESSAGE))

    suspend inline fun <reified T> send(
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
    ): Result<T> = runSuspendCatching { OutfitStore.handle(method.value, path, body) as T }

    @Suppress("UnusedParameter")
    fun failureMessage(error: Throwable, notFound: String, conflict: String, badRequest: String): String = when (error) {
        is OutfitException -> error.message ?: FAILED_MESSAGE
        is MojangSkinException -> error.message ?: FAILED_MESSAGE
        else -> FAILED_MESSAGE
    }
}
