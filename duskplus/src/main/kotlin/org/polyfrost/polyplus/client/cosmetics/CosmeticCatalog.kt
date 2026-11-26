package org.polyfrost.polyplus.client.cosmetics

import org.polyfrost.polyplus.libs.ktor.client.request.get
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.LogManager
import org.polyfrost.polyplus.client.network.http.responses.BodySlot
import org.polyfrost.polyplus.client.network.http.responses.CosmeticDefinition
import org.polyfrost.polyplus.client.network.http.responses.CosmeticGroupResponse
import org.polyfrost.polyplus.client.network.http.responses.CosmeticType
import org.polyfrost.polyplus.client.network.http.responses.EquippedCosmetics
import org.polyfrost.polyplus.client.network.http.responses.PartialEquippedCosmetics
import org.polyfrost.polyplus.client.utils.ClientPlatform
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

object CosmeticCatalog {
    private val LOGGER = LogManager.getLogger()
    private val lock = Mutex()

    private val cosmeticDefinitions = ConcurrentHashMap<Int, CosmeticDefinition>()
    private val emoteDefinitions = ConcurrentHashMap<Int, CosmeticDefinition>()

    private val groupMeta = ConcurrentHashMap<Int, GroupMeta>()
    private val remoteEquipped = ConcurrentHashMap<UUID, Map<BodySlot, Int>>()
    private val particleColors = ConcurrentHashMap<UUID, Int>()
    private val polyPlusUsers = ConcurrentHashMap.newKeySet<UUID>()
    private var localEquipped: EquippedCosmetics = EquippedCosmetics()
    private var selectedEmoteId: Int? = null
    private var ownedCosmeticIds: Set<Int> = emptySet()
    private var ownedEmoteIds: Set<Int> = emptySet()

    fun getDefinition(id: Int): CosmeticDefinition? = cosmeticDefinitions[id] ?: emoteDefinitions[id]

    fun getCosmeticDefinition(id: Int): CosmeticDefinition? = cosmeticDefinitions[id]

    fun getEmoteDefinition(id: Int): CosmeticDefinition? = emoteDefinitions[id]

    fun allDefinitions(): Collection<CosmeticDefinition> = cosmeticDefinitions.values + emoteDefinitions.values

    fun allEmoteDefinitions(): Collection<CosmeticDefinition> = emoteDefinitions.values

    private fun accountId(uuid: UUID): UUID =
        if (uuid == Minecraft.getInstance().player?.uuid) ClientPlatform.localPlayerUuid() else uuid

    fun getRemoteEquipped(uuid: UUID): Map<BodySlot, Int>? = remoteEquipped[accountId(uuid)]

    fun remoteEquippedIds(): Set<Int> = remoteEquipped.values.flatMapTo(HashSet()) { it.values }

    fun getParticleColor(uuid: UUID): Int? = particleColors[accountId(uuid)]

    fun setParticleColor(uuid: UUID, color: Int) {
        particleColors[uuid] = color
    }

    fun clearParticleColor(uuid: UUID) {
        particleColors.remove(uuid)
    }

    fun isPolyPlusUser(uuid: UUID): Boolean = accountId(uuid) in polyPlusUsers

    fun setPolyPlusUser(uuid: UUID, online: Boolean) {
        if (online) polyPlusUsers.add(uuid) else polyPlusUsers.remove(uuid)
    }

    fun clearPolyPlusUser(uuid: UUID) {
        polyPlusUsers.remove(uuid)
    }

    fun getActiveId(uuid: UUID, slot: BodySlot): Int? =
        remoteEquipped[accountId(uuid)]?.get(slot)

    fun localEquipped(): EquippedCosmetics = localEquipped

    fun selectedEmoteId(): Int? = selectedEmoteId

    fun setSelectedEmote(id: Int?) {
        selectedEmoteId = id?.takeIf { it in ownedEmoteIds }
    }

    fun ownedIds(): Set<Int> = ownedCosmeticIds + ownedEmoteIds

    fun ownedCosmeticIds(): Set<Int> = ownedCosmeticIds

    fun ownedEmoteIds(): Set<Int> = ownedEmoteIds

    /** Dusk: OneClient's cosmetics belong to Polyfrost and are not fetched. DuskClient draws Dusk's own. */
    suspend fun refreshCatalog() {
        CosmeticLoadProgress.onCatalogFetched()
    }

    internal fun isRuntimeMismatch(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is LinkageError) return true
            val next = cause.cause
            if (next === cause) break
            cause = next
        }
        return false
    }

    suspend fun refreshPlayer() = Unit

    suspend fun setEquipped(partial: PartialEquippedCosmetics): Result<Unit> = Result.success(Unit)

    fun applyRemoteActive(uuid: UUID, cosmeticIds: List<Int>) {
        val map = mutableMapOf<BodySlot, Int>()
        for (id in cosmeticIds) {
            val definition = getCosmeticDefinition(id) ?: continue
            val slot = definition.preferredSlot() ?: continue
            map[slot] = id
        }
        applyRemoteEquipped(uuid, map)
    }

    fun applyRemoteEquipped(uuid: UUID, equipment: Map<BodySlot, Int>) {
        val known = equipment.filterKeys { it != BodySlot.Unknown }
        if (known.isEmpty()) {
            remoteEquipped.remove(uuid)
        } else {
            remoteEquipped[uuid] = known
        }
    }

    fun applyRemoteEquippedSlot(uuid: UUID, slot: BodySlot, cosmeticId: Int?) {
        if (slot == BodySlot.Unknown) return
        val next = remoteEquipped[uuid].orEmpty().toMutableMap()
        if (cosmeticId == null) {
            next.remove(slot)
        } else {
            next[slot] = cosmeticId
        }
        applyRemoteEquipped(uuid, next)
    }

    fun removeRemote(uuid: UUID) {
        remoteEquipped.remove(uuid)
        particleColors.remove(uuid)
        polyPlusUsers.remove(uuid)
    }

    // One entry per buyable cosmetic emotes are excluded and have their own list
    fun cosmeticGroupViews(): List<CosmeticGroupView> =
        groupMeta.values.mapNotNull { meta ->
            val variants = meta.variantIds.mapNotNull { cosmeticDefinitions[it] }
            if (variants.isEmpty()) {
                null
            } else {
                CosmeticGroupView(meta.id, meta.type, meta.name, meta.allowedSlots, variants)
            }
        }

    fun groupContaining(id: Int): CosmeticGroupView? =
        cosmeticGroupViews().firstOrNull { group ->
            group.groupId == id || group.variants.any { it.id == id }
        }

    // Label to representative variant id in backend order with the slim/wide axis collapsed away
    fun userFacingVariants(group: CosmeticGroupView): List<Pair<String, Int>> {
        val byLabel = LinkedHashMap<String, Int>()
        for (variant in group.variants) {
            byLabel.getOrPut(variant.variantName) { variant.id }
        }
        return byLabel.map { it.key to it.value }
    }

    fun ownedCosmeticGroupIds(): List<Int> =
        cosmeticGroupViews()
            .filter { group -> group.variants.any { it.id in ownedCosmeticIds } }
            .map { it.groupId }

    fun resolveVariantForSkin(variantId: Int, slim: Boolean): Int {
        val definition = cosmeticDefinitions[variantId] ?: return variantId
        if (definition.model == null) return variantId
        val meta = groupMeta[definition.groupId] ?: return variantId
        val wanted = if (slim) "slim" else "wide"
        val sibling = meta.variantIds
            .asSequence()
            .mapNotNull { cosmeticDefinitions[it] }
            .firstOrNull { it.variantName == definition.variantName && it.model == wanted }
        return sibling?.id ?: variantId
    }

    fun reset() {
        cosmeticDefinitions.clear()
        emoteDefinitions.clear()
        groupMeta.clear()
        remoteEquipped.clear()
        particleColors.clear()
        polyPlusUsers.clear()
        localEquipped = EquippedCosmetics()
        selectedEmoteId = null
        ownedCosmeticIds = emptySet()
        ownedEmoteIds = emptySet()
    }

    private data class GroupMeta(
        val id: Int,
        val type: CosmeticType,
        val name: String,
        val allowedSlots: List<BodySlot>,
        val variantIds: List<Int>,
    )

    private fun CosmeticGroupResponse.toMeta(): GroupMeta =
        GroupMeta(id, type, name, allowedSlots, variants.map { it.id })
}

data class CosmeticGroupView(
    val groupId: Int,
    val type: CosmeticType,
    val name: String,
    val allowedSlots: List<BodySlot>,
    val variants: List<CosmeticDefinition>,
)
