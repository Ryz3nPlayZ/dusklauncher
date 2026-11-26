package org.polyfrost.polyplus.client.features

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.polyfrost.polyplus.client.PolyPlusConfig

object BadMods {

    @Serializable
    data class ProjectIds(val modrinth: String? = null, val curseforge: String? = null)

    @Serializable
    data class Alternative(
        val hash: String? = null,
        @SerialName("project-ids") val projectIds: ProjectIds = ProjectIds(),
    )

    @Serializable
    data class Entry(
        val hash: String? = null,
        @SerialName("project-ids") val projectIds: ProjectIds = ProjectIds(),
        val name: String? = null,
        val author: String? = null,
        val explanation: String? = null,
        val alternatives: List<Alternative> = emptyList(),
    )

    @Serializable
    data class BadModList(@SerialName("bad-mods") val badMods: List<Entry> = emptyList())

    data class InstalledMod(
        val id: String,
        val name: String,
        val authors: List<String>,
        val sha1: String,
        val modrinthProjectId: String?,
    )

    data class Detection(val entry: Entry, val mod: InstalledMod)

    data class Warning(
        val id: String,
        val name: String,
        val message: String,
        val alternative: Pair<String, String>?,
    )

    var warnings by mutableStateOf<List<Warning>>(emptyList())
        private set

    fun dismiss() {
        warnings = emptyList()
    }

    fun ignoreShown() {
        PolyPlusConfig.ignoredBadMods = (ignored() + warnings.map { it.id }).joinToString(",")
        PolyPlusConfig.save()
        dismiss()
    }

    private fun ignored(): Set<String> = PolyPlusConfig.ignoredBadMods.split(',').filter(String::isNotEmpty).toSet()

    @JvmStatic
    fun detect(list: BadModList, installed: List<InstalledMod>): List<Detection> =
        list.badMods.flatMap { entry -> installed.filter { matches(entry, it) }.map { Detection(entry, it) } }

    @JvmStatic
    fun matches(entry: Entry, mod: InstalledMod): Boolean {
        if (entry.hash != null && entry.hash.equals(mod.sha1, ignoreCase = true)) return true
        val modrinth = entry.projectIds.modrinth
        if (modrinth != null && modrinth == mod.modrinthProjectId) return true
        // CurseForge fingerprints need an API key, so entries without a hash or Modrinth ID fall back to name and author
        if (entry.hash != null || modrinth != null) return false
        val name = entry.name ?: return false
        if (!name.equals(mod.name, ignoreCase = true)) return false
        val author = entry.author ?: return true
        return mod.authors.any { it.equals(author, ignoreCase = true) }
    }

    @JvmStatic
    fun plainText(markdown: String): String = markdown.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .joinToString(" ")
        .replace(Regex("""\[([^]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""[*`]"""), "")
}
