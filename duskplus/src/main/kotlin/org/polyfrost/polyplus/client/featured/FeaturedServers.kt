package org.polyfrost.polyplus.client.featured

import net.fabricmc.loader.api.FabricLoader
import org.apache.logging.log4j.LogManager
import org.polyfrost.polyplus.client.PolyPlusClient
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object FeaturedServers {

    private val logger = LogManager.getLogger("PolyPlus/FeaturedServers")
    private val lock = Any()
    private val _state = MutableStateFlow(FeaturedServersSnapshot())
    @Volatile
    private var loaded = false
    @Volatile
    private var committed = FeaturedServersSnapshot()
    private var expiryJob: Job? = null

    @JvmField
    val state: StateFlow<FeaturedServersSnapshot> = _state.asStateFlow()

    private val dismissalFile: File
        get() = File(FabricLoader.getInstance().gameDir.toFile(), "polyplus/featured_server_dismissals.json")

    @JvmStatic
    fun warmUp() {
        ensureLoaded()
    }

    // Dusk: no featured-server catalog is fetched, so the list stays empty
    @JvmStatic
    fun refresh(force: Boolean = false) = ensureLoaded()

    @JvmStatic
    fun dismissMainMenu(campaignId: String) {
        if (campaignId.isBlank()) return
        ensureLoaded()
        val snapshot = synchronized(lock) {
            val current = committed
            if (campaignId in current.mainMenuDismissedCampaignIds) return
            if (!current.isMainMenuDismissible(campaignId)) return
            val mainMenuDismissed = current.mainMenuDismissedCampaignIds + campaignId
            val next = commitLocked(
                current.servers,
                current.expiresAtMillis,
                mainMenuDismissed,
                current.multiplayerDismissedCampaignIds,
            )
            persistDismissals(Dismissals(mainMenuDismissed, current.multiplayerDismissedCampaignIds))
            next
        }
        publish(snapshot)
    }

    @JvmStatic
    @JvmOverloads
    fun dismissMultiplayer(campaignId: String, force: Boolean = false) {
        if (campaignId.isBlank()) return
        ensureLoaded()
        val snapshot = synchronized(lock) {
            val current = committed
            if (campaignId in current.multiplayerDismissedCampaignIds) return
            if (!force && !current.isMultiplayerDismissible(campaignId)) return
            val multiplayerDismissed = current.multiplayerDismissedCampaignIds + campaignId
            val next = commitLocked(
                current.servers,
                current.expiresAtMillis,
                current.mainMenuDismissedCampaignIds,
                multiplayerDismissed,
            )
            persistDismissals(Dismissals(current.mainMenuDismissedCampaignIds, multiplayerDismissed))
            next
        }
        publish(snapshot)
    }

    @JvmStatic
    fun restoreMultiplayer(campaignId: String) {
        if (campaignId.isBlank()) return
        ensureLoaded()
        val snapshot = synchronized(lock) {
            val current = committed
            if (campaignId !in current.multiplayerDismissedCampaignIds) return
            val multiplayerDismissed = current.multiplayerDismissedCampaignIds - campaignId
            val next = commitLocked(
                current.servers,
                current.expiresAtMillis,
                current.mainMenuDismissedCampaignIds,
                multiplayerDismissed,
            )
            persistDismissals(Dismissals(current.mainMenuDismissedCampaignIds, multiplayerDismissed))
            next
        }
        publish(snapshot)
    }

    @JvmStatic
    fun isMultiplayerRestorable(server: FeaturedServer): Boolean = snapshot().isMultiplayerRestorable(server)

    @JvmStatic
    fun dismissMultiplayerByAddress(address: String) {
        if (address.isBlank()) return
        val normalized = normalizeServerAddress(address)
        val campaign = snapshot().visibleServers()
            .firstOrNull { normalizeServerAddress(it.address) == normalized }
            ?.featured ?: return
        dismissMultiplayer(campaign.campaignId, force = true)
    }

    @JvmStatic
    fun iconUrl(serverId: String): String = iconUrl("https://disabled.invalid/servers.json", serverId)

    internal fun iconUrl(catalogUrl: String, serverId: String): String {
        val id = URLEncoder.encode(serverId, StandardCharsets.UTF_8).replace("+", "%20")
        return "${catalogUrl.removeSuffix(".json")}/$id.png"
    }

    @JvmStatic
    fun snapshot(): FeaturedServersSnapshot {
        ensureLoaded()
        return committed
    }

    @JvmStatic
    fun revision(): Long = snapshot().revision

    private fun ensureLoaded() {
        if (loaded) return
        val initial = synchronized(lock) {
            if (loaded) return
            val dismissals = runCatching {
                dismissalFile.takeIf(File::isFile)?.readText()?.let {
                    PolyPlusClient.JSON.decodeFromString(Dismissals.serializer(), it)
                }
            }.getOrNull() ?: Dismissals()
            val first = commitLocked(
                emptyList(),
                0L,
                dismissals.mainMenuCampaignIds,
                dismissals.multiplayerCampaignIds,
            )
            loaded = true
            first
        }
        publish(initial)
    }

    private fun publish(snapshot: FeaturedServersSnapshot) {
        _state.update { current -> if (snapshot.revision > current.revision) snapshot else current }
    }

    private fun commitLocked(
        servers: List<FeaturedServer>,
        expiresAtMillis: Long,
        mainMenuDismissed: Set<String>,
        multiplayerDismissed: Set<String>,
    ): FeaturedServersSnapshot {
        val snapshot = FeaturedServersSnapshot(
            servers.toList(),
            mainMenuDismissed.toSet(),
            multiplayerDismissed.toSet(),
            expiresAtMillis,
            committed.revision + 1,
        )
        committed = snapshot
        expiryJob?.cancel()
        val delayMillis = expiresAtMillis - System.currentTimeMillis()
        if (servers.isNotEmpty() && delayMillis > 0) {
            val expectedExpiry = expiresAtMillis
            expiryJob = PolyPlusClient.SCOPE.launch {
                delay(delayMillis)
                val expired = synchronized(lock) {
                    if (committed.expiresAtMillis != expectedExpiry) return@synchronized null
                    commitLocked(
                        emptyList(),
                        0L,
                        committed.mainMenuDismissedCampaignIds,
                        committed.multiplayerDismissedCampaignIds,
                    )
                }
                if (expired != null) publish(expired)
            }
        } else {
            expiryJob = null
        }
        return snapshot
    }

    private fun persistDismissals(dismissals: Dismissals) = runCatching {
        dismissalFile.parentFile?.mkdirs()
        dismissalFile.writeText(PolyPlusClient.JSON.encodeToString(Dismissals.serializer(), dismissals))
    }.onFailure { logger.warn("Could not persist featured-server dismissals", it) }

    @Serializable
    private data class Dismissals(
        @SerialName("main_menu_campaign_ids") val mainMenuCampaignIds: Set<String> = emptySet(),
        @SerialName("multiplayer_campaign_ids") val multiplayerCampaignIds: Set<String> = emptySet(),
    )
}

object FeaturedServerCachePolicy {
    const val CACHE_TTL_MILLIS: Long = 24L * 60L * 60L * 1_000L
    const val REFRESH_INTERVAL_MILLIS: Long = 15L * 60L * 1_000L

    fun expiresAt(fetchedAtMillis: Long): Long = fetchedAtMillis + CACHE_TTL_MILLIS

    fun isFresh(fetchedAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis >= fetchedAtMillis && nowMillis < expiresAt(fetchedAtMillis)

    fun shouldRefresh(lastAttemptMillis: Long, nowMillis: Long, force: Boolean): Boolean =
        force || lastAttemptMillis == 0L || nowMillis - lastAttemptMillis >= REFRESH_INTERVAL_MILLIS
}
