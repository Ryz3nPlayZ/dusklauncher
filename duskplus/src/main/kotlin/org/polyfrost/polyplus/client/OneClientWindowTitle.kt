package org.polyfrost.polyplus.client

import net.fabricmc.loader.api.FabricLoader
import org.polyfrost.polyplus.privacy.LauncherEnvironment

object OneClientWindowTitle {
    private const val BRAND = "Dusk Client"
    private const val BRAND_PROPERTY = "minecraft.launcher.brand"
    private const val VERSION_PROPERTY = "minecraft.launcher.version"
    private val LAUNCHER_BRANDS = listOf(BRAND, "DuskLauncher")

    private val title: String? by lazy {
        runCatching { if (LauncherEnvironment.isOneClient) build() else null }.getOrNull()
    }

    @JvmStatic
    fun apply(vanilla: String): String = title ?: vanilla

    private fun build(): String {
        val client = launcherVersion()?.let { "$BRAND $it" } ?: BRAND
        val minecraft = FabricLoader.getInstance().getModContainer("minecraft")
            .map { it.metadata.version.friendlyString }
            .orElse("")
        return if (minecraft.isBlank()) client else "$client - Minecraft $minecraft"
    }

    private fun launcherVersion(): String? {
        // Dusk: the Dusk launcher sends its own name as the brand
        val brand = System.getProperty(BRAND_PROPERTY)?.trim() ?: return null
        if (LAUNCHER_BRANDS.none { brand.equals(it, ignoreCase = true) }) return null
        return System.getProperty(VERSION_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("$") }
    }
}
