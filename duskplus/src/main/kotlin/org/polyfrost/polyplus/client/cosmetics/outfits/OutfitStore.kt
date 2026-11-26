package org.polyfrost.polyplus.client.cosmetics.outfits

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.loader.api.FabricLoader
import org.apache.logging.log4j.LogManager
import org.polyfrost.polyplus.client.dusk.DuskBridge
import org.polyfrost.polyplus.client.utils.ClientPlatform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Dusk: the skin and outfit libraries, kept in a file per account instead of on Polyfrost's servers. */
internal object OutfitStore {
    private val LOGGER = LogManager.getLogger("PolyPlus/Outfits")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()

    @Serializable
    private data class Library(
        val nextId: Int = 1,
        val activeOutfitId: Int? = null,
        val outfits: List<OutfitResponse> = emptyList(),
        val skins: List<SkinResponse> = emptyList(),
    )

    suspend fun handle(method: String, path: String, body: JsonObject?): Any = withContext(Dispatchers.IO) {
        lock.withLock {
            val file = file()
            val library = read(file)
            val parts = path.trim('/').split('/')
            val id = parts.getOrNull(1)?.toIntOrNull()
            val fields = body ?: JsonObject(emptyMap())
            val (changed, answer) = when (parts[0]) {
                "outfits" -> outfits(library, method, id, parts.getOrNull(2) == "apply", fields)
                "skins" -> skins(library, method, id, fields)
                else -> error("Unknown path $path")
            }
            if (changed != library) write(file, changed)
            answer
        }
    }

    private fun outfits(library: Library, method: String, id: Int?, apply: Boolean, body: JsonObject): Pair<Library, Any> {
        val now = Instant.now().toString()
        if (id == null) {
            if (method == "GET") return library to OutfitListResponse(library.outfits, library.activeOutfitId)
            if (library.outfits.size >= LIBRARY_LIMIT) {
                throw OutfitException("You've reached the limit of $LIBRARY_LIMIT outfits. Delete one to make room.")
            }
            val created = change(
                OutfitResponse(
                    id = library.nextId,
                    name = freeName("Outfit", library.outfits.map { it.name }),
                    createdAt = now,
                    updatedAt = now,
                ),
                body,
                now,
            )
            return library.copy(nextId = created.id + 1, activeOutfitId = created.id, outfits = library.outfits + created) to created
        }
        val outfit = library.outfits.firstOrNull { it.id == id } ?: throw OutfitException("That outfit no longer exists.")
        if (apply) {
            val skin = library.skins.firstOrNull { it.id == outfit.skinId }
            return library.copy(activeOutfitId = id) to ApplyOutfitResponse(outfit, skin, adopted = outfit.blank)
        }
        if (method == "DELETE") {
            val active = library.activeOutfitId.takeIf { it != id }
            return library.copy(activeOutfitId = active, outfits = library.outfits - outfit) to Unit
        }
        val changed = change(outfit, body, now)
        return library.copy(outfits = library.outfits.map { if (it.id == id) changed else it }) to changed
    }

    private fun skins(library: Library, method: String, id: Int?, body: JsonObject): Pair<Library, Any> {
        if (id == null) {
            if (method == "GET") return library to SkinListResponse(library.skins)
            val hash = body.text("texture_hash")?.lowercase() ?: throw OutfitException("Minecraft didn't report a usable skin texture.")
            library.skins.firstOrNull { it.hash == hash }?.let { return library to it }
            if (library.skins.size >= LIBRARY_LIMIT) {
                throw OutfitException("You've reached the limit of $LIBRARY_LIMIT skins. Delete one to make room.")
            }
            val created = SkinResponse(
                id = library.nextId,
                name = body.text("name") ?: freeName("Skin", library.skins.map { it.name }),
                model = body.text("model") ?: SkinModel.Classic.serializedName,
                url = "https://textures.minecraft.net/texture/$hash",
                hash = hash,
                createdAt = Instant.now().toString(),
            )
            return library.copy(nextId = created.id + 1, skins = library.skins + created) to created
        }
        val skin = library.skins.firstOrNull { it.id == id } ?: throw OutfitException("That skin no longer exists.")
        if (method == "DELETE") {
            val outfits = library.outfits.map { if (it.skinId == id) it.copy(skinId = null) else it }
            return library.copy(skins = library.skins - skin, outfits = outfits) to Unit
        }
        val changed = skin.copy(
            name = body.text("name") ?: skin.name,
            model = body.text("model") ?: skin.model,
            favorite = body.flag("favorite") ?: skin.favorite,
        )
        return library.copy(skins = library.skins.map { if (it.id == id) changed else it }) to changed
    }

    // a field sent as null clears it; one that isn't sent stays
    private fun change(outfit: OutfitResponse, body: JsonObject, now: String) = outfit.copy(
        name = body.text("name") ?: outfit.name,
        favorite = body.flag("favorite") ?: outfit.favorite,
        skinId = if ("skin_id" in body) body["skin_id"]?.jsonPrimitive?.intOrNull else outfit.skinId,
        mojangCapeId = if ("mojang_cape_id" in body) body.text("mojang_cape_id") else outfit.mojangCapeId,
        particleColor = if ("particle_color" in body) body["particle_color"]?.jsonPrimitive?.intOrNull else outfit.particleColor,
        blank = body.flag("blank") ?: outfit.blank,
        updatedAt = now,
    )

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.flag(key: String): Boolean? = get(key)?.jsonPrimitive?.booleanOrNull

    private fun freeName(kind: String, taken: List<String>): String =
        generateSequence(taken.size + 1) { it + 1 }.map { "$kind $it" }.first { it !in taken }

    private fun file(): Path {
        val dir = DuskBridge.dataDir() ?: FabricLoader.getInstance().configDir
        return dir.resolve("dusk-outfits").resolve("${ClientPlatform.localPlayerUuid()}.json")
    }

    private fun read(file: Path): Library {
        if (!Files.isRegularFile(file)) return Library()
        return try {
            json.decodeFromString(Library.serializer(), Files.readString(file))
        } catch (e: kotlinx.serialization.SerializationException) {
            LOGGER.error("The outfit library {} couldn't be read; it was set aside", file, e)
            Files.move(file, file.resolveSibling("${file.fileName}.bad"), StandardCopyOption.REPLACE_EXISTING)
            Library()
        }
    }

    private fun write(file: Path, library: Library) {
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temp, json.encodeToString(Library.serializer(), library))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
    }
}
