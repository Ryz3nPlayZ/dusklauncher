package dev.dusk.client.cosmetics;

import com.mojang.authlib.GameProfile;
import dev.dusk.client.config.DuskConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * Per-player cosmetics, resolved the same way as on 1.21.11:
 * <ol>
 *   <li>the local player: the launcher-written loadout, from the registry
 *       bundled in this jar;</li>
 *   <li>other players: the loadout they published through the Dusk service;</li>
 *   <li>anyone with nothing equipped: their MinecraftCapes profile, unless off;</li>
 *   <li>otherwise vanilla.</li>
 * </ol>
 * The client thread only reads {@link Entry#current}, an immutable
 * {@link PlayerCosmetics}. Decoding and downloads run on one daemon worker;
 * textures are uploaded and the entry swapped in a scheduled task on the
 * client thread.
 */
public final class CosmeticsManager {
    private static final Logger LOG = LogManager.getLogger("duskclient/cosmetics");
    private static final long TTL_MS = 5 * 60_000L;
    private static final long NEGATIVE_TTL_MS = 10 * 60_000L;

    private static final ConcurrentHashMap<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "duskclient-cosmetics");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final Set<UUID> DUSK_USERS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final class Entry {
        volatile PlayerCosmetics current = PlayerCosmetics.NONE;
        volatile long refreshAt = 0;
        final AtomicBoolean loading = new AtomicBoolean();
        /** what {@link #current} was built from; an unchanged refresh keeps the textures */
        volatile String signature;
    }

    private CosmeticsManager() {}

    public static void init() {
        LOG.info("Cosmetics ready: {} bundled cape(s), {} accessor(ies)",
                CapeRegistry.capes().size(), CapeRegistry.accessories().size());
    }

    /** What to draw for this player now; starts a (re)load when stale. */
    public static PlayerCosmetics get(UUID uuid, String name) {
        return entry(uuid, name).current;
    }

    public static PlayerCosmetics get(AbstractClientPlayer player) {
        GameProfile p = player.getGameProfile();
        return get(player.getUniqueID(), p == null ? null : p.getName());
    }

    /**
     * The cape {@code getLocationCape} should report: ours, {@code fallback}
     * (vanilla's), or null when official capes are hidden.
     */
    public static ResourceLocation capeFor(AbstractClientPlayer player, ResourceLocation fallback) {
        ResourceLocation ours = get(player).capeTexture();
        if (ours != null) return ours;
        return fallback != null && DuskConfig.get().cosmetics.hideOfficialCapes ? null : fallback;
    }

    /** The signed-in player's cape for screens with no world (the title model), or null. */
    public static ResourceLocation localCape() {
        try {
            GameProfile me = Minecraft.getMinecraft().getSession().getProfile();
            if (me == null || me.getId() == null) return null;
            return get(me.getId(), me.getName()).capeTexture();
        } catch (RuntimeException e) {
            return null; // an offline session id that isn't a UUID
        }
    }

    public static boolean isDuskUser(UUID uuid) {
        return DUSK_USERS.contains(uuid);
    }

    /** Re-resolve the local player (after the loadout changed). */
    public static void reloadLocal() {
        for (java.util.Map.Entry<UUID, Entry> e : ENTRIES.entrySet()) {
            if (isLocal(e.getKey(), null)) e.getValue().refreshAt = 0;
        }
    }

    /** Re-resolve everyone (a cosmetics setting changed). */
    public static void reloadAll() {
        for (Entry e : ENTRIES.values()) {
            e.refreshAt = 0;
            e.signature = null;
        }
    }

    /** Drop everything (disconnect). Any thread; the textures go on the client thread. */
    public static void clear() {
        final List<Entry> old = new ArrayList<>(ENTRIES.values());
        ENTRIES.clear();
        DUSK_USERS.clear();
        Minecraft.getMinecraft().addScheduledTask(() -> {
            for (Entry e : old) e.current.release();
        });
    }

    private static Entry entry(UUID uuid, String name) {
        Entry e = ENTRIES.computeIfAbsent(uuid, k -> new Entry());
        if (System.currentTimeMillis() >= e.refreshAt && e.loading.compareAndSet(false, true)) {
            boolean local = isLocal(uuid, name);
            WORKER.execute(() -> load(uuid, name, local, e));
        }
        return e;
    }

    static boolean isLocal(UUID uuid, String name) {
        Minecraft mc = Minecraft.getMinecraft();
        try {
            GameProfile me = mc.getSession().getProfile();
            if (me != null && uuid.equals(me.getId())) return true;
        } catch (RuntimeException ignored) {
            // an offline session id that isn't a UUID: fall through to the name
        }
        // offline-mode servers hand out v3 UUIDs; fall back to the name
        return name != null && uuid.version() == 3 && name.equalsIgnoreCase(mc.getSession().getUsername());
    }

    // ── worker thread ─────────────────────────────────────────────────────

    private static void load(UUID uuid, String name, boolean local, Entry e) {
        String signature = null;
        PlayerCosmetics fresh = PlayerCosmetics.NONE;
        try {
            Object[] r = resolve(uuid, name, local, e.signature);
            signature = (String) r[0];
            fresh = (PlayerCosmetics) r[1];
        } catch (Throwable t) {
            LOG.warn("Cosmetics load failed for {}", uuid, t);
        }
        final PlayerCosmetics result = fresh;
        final String sig = signature;
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (result == null) { // unchanged: keep what's drawn
                if (ENTRIES.get(uuid) == e) e.refreshAt = System.currentTimeMillis() + ttl(e.current);
                e.loading.set(false);
                return;
            }
            try {
                result.register();
            } catch (Throwable t) {
                LOG.warn("Texture upload failed for {}", uuid, t);
                result.release();
                finish(uuid, e, PlayerCosmetics.NONE, null);
                return;
            }
            finish(uuid, e, result, sig);
        });
    }

    private static long ttl(PlayerCosmetics c) {
        return c == PlayerCosmetics.NONE ? NEGATIVE_TTL_MS : TTL_MS;
    }

    private static void finish(UUID uuid, Entry e, PlayerCosmetics fresh, String signature) {
        if (ENTRIES.get(uuid) != e) { // cleared (disconnect) while loading
            fresh.release();
            e.loading.set(false);
            return;
        }
        PlayerCosmetics old = e.current;
        e.current = fresh;
        e.signature = signature;
        old.release();
        e.refreshAt = System.currentTimeMillis() + ttl(fresh);
        e.loading.set(false);
    }

    /** {signature, cosmetics}; cosmetics null means "same as before". */
    private static Object[] resolve(UUID uuid, String name, boolean local, String previous) {
        CosmeticsConfig cfg = DuskConfig.get().cosmetics;
        String key = uuid.toString().replace("-", "") + "/" + GENERATION.incrementAndGet();

        // Registry ids: the launcher's loadout for us, the Dusk service's for others.
        int capeId = -1;
        if (local) {
            capeId = cfg.capeId();
            DUSK_USERS.add(uuid);
        } else if (cfg.showOthers) {
            DuskProvider.Loadout l = DuskProvider.fetch(uuid);
            if (l != null && l.dusk) DUSK_USERS.add(uuid);
            else DUSK_USERS.remove(uuid);
            if (l != null) capeId = l.cape;
        }

        CapeRegistry.CapeEntry entry = CapeRegistry.cape(capeId);
        MinecraftCapesProvider.Profile mcc = null;
        if (entry == null) {
            if (capeId >= 0) LOG.debug("Unknown registry cape {} for {} (older jar?)", capeId, uuid);
            if (cfg.minecraftCapes && (local || cfg.showOthers)) {
                MinecraftCapesProvider.Profile p = MinecraftCapesProvider.fetch(uuid, name);
                if (p != null && !p.isEmpty()) mcc = p;
            }
        }
        String signature = entry != null ? "dusk:" + capeId
                : mcc != null ? "mcc:" + crc(mcc.cape) + "/" + crc(mcc.ears) + "/" + mcc.glint + mcc.upsideDown
                : "none";
        if (signature.equals(previous)) return new Object[] {signature, null};

        if (entry != null) {
            byte[] png = CapeRegistry.texture(capeId, "cape.png");
            CapeTexture cape = png == null ? null : CapeTexture.prepareCape(key, png, entry.frameMs);
            if (cape == null) {
                LOG.warn("Registry cape {} has no usable cape.png", capeId);
            } else {
                CapeTexture ears = null;
                if (entry.ears) {
                    byte[] earPng = CapeRegistry.texture(capeId, "ears.png");
                    ears = earPng == null ? null : CapeTexture.prepareEars(key, earPng);
                }
                return new Object[] {signature, PlayerCosmetics.of(cape, ears, entry.glint, entry.upsideDown)};
            }
        } else if (mcc != null) {
            CapeTexture cape = mcc.cape == null ? null : CapeTexture.prepareCape(key, mcc.cape);
            CapeTexture ears = mcc.ears == null ? null : CapeTexture.prepareEars(key, mcc.ears);
            return new Object[] {signature, PlayerCosmetics.of(cape, ears, mcc.glint, mcc.upsideDown)};
        }
        return new Object[] {signature, PlayerCosmetics.NONE};
    }

    private static long crc(byte[] bytes) {
        if (bytes == null) return -1;
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }
}
