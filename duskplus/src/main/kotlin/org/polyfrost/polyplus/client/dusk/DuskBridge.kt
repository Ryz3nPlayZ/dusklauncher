package org.polyfrost.polyplus.client.dusk

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.polyfrost.polyplus.privacy.PrivacyConsent
import java.io.IOException
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Path

/** What the Dusk service answered, in words fit for the player. */
class DuskException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The Dusk service as the signed-in player, through DuskClient's `DuskAccount` (which owns the
 * address, the sign-in and the token). Reached by name, so neither mod needs the other to build.
 */
object DuskBridge {
    private const val ACCOUNT = "dev.dusk.client.account.DuskAccount"

    private val account: Class<*>? = runCatching { Class.forName(ACCOUNT) }.getOrNull()

    private val api: MethodHandle? = handle("api", JsonElement::class.java, String::class.java, String::class.java, JsonElement::class.java)
    private val canSignIn: MethodHandle? = handle("canSignIn", Boolean::class.javaPrimitiveType!!)
    private val forget: MethodHandle? = handle("forget", Void.TYPE)
    private val dataDir: MethodHandle? = handle("dataDir", Path::class.java)
    private val bearer: MethodHandle? = handle("bearer", String::class.java, Boolean::class.javaPrimitiveType!!)

    private val apiBase: MethodHandle? = runCatching {
        val provider = Class.forName("dev.dusk.client.cosmetics.DuskProvider", false, DuskBridge::class.java.classLoader)
        MethodHandles.publicLookup().findStatic(provider, "apiBase", MethodType.methodType(String::class.java))
    }.getOrNull()

    private fun handle(name: String, returns: Class<*>, vararg params: Class<*>): MethodHandle? = runCatching {
        MethodHandles.publicLookup().findStatic(account ?: return null, name, MethodType.methodType(returns, params))
    }.getOrNull()

    // looked up without initialising it: the class is only on 1.21.11 and newer
    private val replayActive: MethodHandle? = runCatching {
        val player = Class.forName("dev.dusk.client.media.impl.ReplayPlayer", false, DuskBridge::class.java.classLoader)
        MethodHandles.publicLookup().findStatic(player, "active", MethodType.methodType(Boolean::class.javaPrimitiveType!!))
    }.getOrNull()

    /** Whether DuskClient is playing one of its replays; Esc then opens its replay controls, not the pause menu. */
    val inReplay: Boolean
        get() = runCatching { replayActive?.invokeWithArguments() == true }.getOrDefault(false)

    /** Whether there's a Dusk account to act as. */
    val available: Boolean
        get() = api != null && runCatching { canSignIn?.invokeWithArguments() == true }.getOrDefault(false)

    /** The launcher's data directory; null outside the launcher. */
    fun dataDir(): Path? = runCatching { dataDir?.invokeWithArguments() as Path? }.getOrNull()

    /** Where the Dusk service is, without a trailing slash. */
    fun apiBase(): String? = runCatching { (apiBase?.invokeWithArguments() as String?)?.trimEnd('/') }.getOrNull()

    /** The session token, for calls made here rather than through DuskClient; [fresh] signs in again. Throws [DuskException]. */
    suspend fun bearer(fresh: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!PrivacyConsent.allowsOnlineServices()) throw DuskException("Online features are turned off")
        val handle = bearer ?: throw DuskException("Dusk Client isn't installed")
        if (!available) throw DuskException("Sign in to use Dusk's online features")
        try {
            handle.invokeWithArguments(fresh) as String
        } catch (e: IOException) {
            throw DuskException(e.message ?: "Couldn't reach Dusk", e)
        }
    }

    fun forget() {
        runCatching { forget?.invokeWithArguments() }
    }

    /** One JSON call, off the calling thread. Throws [DuskException]. */
    suspend fun call(method: String, path: String, body: JsonElement? = null): JsonElement? = withContext(Dispatchers.IO) {
        if (!PrivacyConsent.allowsOnlineServices()) throw DuskException("Online features are turned off")
        val handle = api ?: throw DuskException("Dusk Client isn't installed")
        if (!available) throw DuskException("Sign in to use Dusk's online features")
        try {
            handle.invokeWithArguments(method, path, body) as JsonElement?
        } catch (e: IOException) {
            throw DuskException(e.message ?: "Couldn't reach Dusk", e)
        }
    }

    suspend fun obj(method: String, path: String, body: JsonElement? = null): JsonObject =
        call(method, path, body) as? JsonObject ?: throw DuskException("Dusk sent something unexpected")

    suspend fun array(method: String, path: String, body: JsonElement? = null): JsonArray =
        call(method, path, body) as? JsonArray ?: throw DuskException("Dusk sent something unexpected")
}

internal fun json(vararg fields: Pair<String, Any?>): JsonObject = JsonObject().apply {
    fields.forEach { (key, value) ->
        when (value) {
            null -> Unit
            is JsonElement -> add(key, value)
            is Boolean -> addProperty(key, value)
            is Number -> addProperty(key, value)
            else -> addProperty(key, value.toString())
        }
    }
}

internal fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
internal fun JsonObject.long(key: String): Long = get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
internal fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
internal fun JsonObject.list(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
internal fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }
private fun JsonArray?.orEmpty(): Iterable<JsonElement> = this ?: emptyList()
