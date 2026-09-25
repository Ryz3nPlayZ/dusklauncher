package dev.dusk.client.cosmetics;

import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.cosmetics.model.AccessoryModel;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * Per-player cosmetics state (docs/COSMETICS.md §2).
 *
 * <p>Resolution order for a player:
 * <ol>
 *   <li>the local player: the launcher-written loadout → registry
 *       cosmetics bundled in this jar;</li>
 *   <li>other players: the loadout they published through the Dusk
 *       service ({@link DuskProvider}) → the same registry;</li>
 *   <li>anyone (when nothing is equipped): their MinecraftCapes profile,
 *       unless disabled;</li>
 *   <li>otherwise vanilla.</li>
 * </ol>
 *
 * <p>Threading: the render thread only ever reads {@link Entry#current}, an
 * immutable {@link PlayerCosmetics}. Decoding/downloading happens on one
 * daemon worker; textures are registered and the entry swapped in a task
 * on the render thread, so a frame never observes a half-loaded state.
 */
public final class CosmeticsManager {
    private static final Logger LOG = LoggerFactory.getLogger("duskclient/cosmetics");
    private static final long TTL_MS = 5 * 60_000L;
    private static final long NEGATIVE_TTL_MS = 10 * 60_000L;

    private static final ConcurrentHashMap<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "duskclient-cosmetics");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicLong GENERATION = new AtomicLong();

    private static final class Entry {
        volatile PlayerCosmetics current = PlayerCosmetics.NONE;
        volatile long refreshAt = 0;
        final AtomicBoolean loading = new AtomicBoolean();
        // what {@link #current} was built from; a refresh that resolves to the
        // same thing keeps the textures instead of decoding and uploading again
        @Nullable volatile String signature;
        // one-slot cache so getSkin() doesn't allocate a PlayerSkin per frame
        @Nullable PlayerSkin lastIn;
        @Nullable PlayerSkin lastOut;
    }

    private CosmeticsManager() {}

    public static void init() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        LOG.info("Cosmetics ready: {} bundled cape(s), {} accessor(ies)",
                CapeRegistry.capes().size(), CapeRegistry.accessories().size());
    }

    /** What to draw for this player right now; kicks off a (re)load when stale. */
    public static PlayerCosmetics get(UUID uuid, @Nullable String name) {
        return entry(uuid, name).current;
    }

    /**
     * The skin the entity should report: vanilla with the cape (and elytra,
     * like MinecraftCapes) swapped for ours, or with the official cape
     * stripped when the user asked for that. Render thread.
     */
    public static PlayerSkin skinFor(UUID uuid, @Nullable String name, PlayerSkin original) {
        Entry e = entry(uuid, name);
        PlayerCosmetics c = e.current;
        if (c.hasCape()) {
            if (e.lastIn == original && e.lastOut != null) return e.lastOut;
            ResourceLocation cape = c.capeTexture();
            PlayerSkin out = new PlayerSkin(original.texture(), original.textureUrl(), cape, cape, original.model(), original.secure());
            e.lastIn = original;
            e.lastOut = out;
            return out;
        }
        if (original.capeTexture() != null && DuskConfig.get().cosmetics.hideOfficialCapes) {
            if (e.lastIn == original && e.lastOut != null) return e.lastOut;
            PlayerSkin out = new PlayerSkin(original.texture(), original.textureUrl(), null, original.elytraTexture(), original.model(), original.secure());
            e.lastIn = original;
            e.lastOut = out;
            return out;
        }
        return original;
    }

    /** Force the local player to be re-resolved (after the loadout changed). */
    public static void reloadLocal() {
        ENTRIES.forEach((id, en) -> {
            if (isLocal(id, null)) en.refreshAt = 0;
        });
    }

    /** Drop everything (disconnect). Render thread. */
    public static void clear() {
        List<Entry> old = List.copyOf(ENTRIES.values());
        ENTRIES.clear();
        Minecraft.getInstance().execute(() -> old.forEach(e -> e.current.release()));
    }

    private static Entry entry(UUID uuid, @Nullable String name) {
        Entry e = ENTRIES.computeIfAbsent(uuid, k -> new Entry());
        long now = System.currentTimeMillis();
        if (now >= e.refreshAt && e.loading.compareAndSet(false, true)) {
            boolean local = isLocal(uuid, name);
            WORKER.execute(() -> load(uuid, name, local, e));
        }
        return e;
    }

    static boolean isLocal(UUID uuid, @Nullable String name) {
        User user = Minecraft.getInstance().getUser();
        if (uuid.equals(user.getProfileId())) return true;
        // offline-mode servers hand out v3 UUIDs; fall back to the name
        return name != null && uuid.version() == 3 && name.equalsIgnoreCase(user.getName());
    }

    // ── worker thread ─────────────────────────────────────────────────────

    /** A resolved player: what it was built from, and the cosmetics (null: same as before). */
    private record Resolved(@Nullable String signature, @Nullable PlayerCosmetics cosmetics) {}

    private static void load(UUID uuid, @Nullable String name, boolean local, Entry e) {
        Resolved r = new Resolved(null, PlayerCosmetics.NONE);
        try {
            r = resolve(uuid, name, local, e.signature);
        } catch (Throwable t) {
            LOG.warn("Cosmetics load failed for {}", uuid, t);
        }
        final PlayerCosmetics fresh = r.cosmetics();
        final String signature = r.signature();
        Minecraft.getInstance().execute(() -> {
            if (fresh == null) { // unchanged: keep what's drawn
                if (ENTRIES.get(uuid) == e) e.refreshAt = System.currentTimeMillis() + (e.current == PlayerCosmetics.NONE ? NEGATIVE_TTL_MS : TTL_MS);
                e.loading.set(false);
                return;
            }
            try {
                fresh.register();
            } catch (Throwable t) {
                LOG.warn("Texture upload failed for {}", uuid, t);
                fresh.release();
                finish(uuid, e, PlayerCosmetics.NONE, null);
                return;
            }
            finish(uuid, e, fresh, signature);
        });
    }

    private static void finish(UUID uuid, Entry e, PlayerCosmetics fresh, @Nullable String signature) {
        if (ENTRIES.get(uuid) != e) { // cleared (disconnect) while loading
            fresh.release();
            e.loading.set(false);
            return;
        }
        PlayerCosmetics old = e.current;
        e.current = fresh;
        e.signature = signature;
        e.lastIn = null;
        e.lastOut = null;
        old.release();
        e.refreshAt = System.currentTimeMillis() + (fresh == PlayerCosmetics.NONE ? NEGATIVE_TTL_MS : TTL_MS);
        e.loading.set(false);
    }

    private static Resolved resolve(UUID uuid, @Nullable String name, boolean local, @Nullable String previous) {
        CosmeticsConfig cfg = DuskConfig.get().cosmetics;
        String key = uuid.toString().replace("-", "") + "/" + GENERATION.incrementAndGet();

        // Registry ids: the launcher's loadout for us, the Dusk service's for others.
        int capeId = -1;
        List<Integer> accessoryIds = List.of();
        if (local) {
            capeId = cfg.capeId();
            accessoryIds = cfg.accessoryIds();
        } else if (cfg.showOthers) {
            DuskProvider.Loadout l = DuskProvider.fetch(uuid);
            if (l != null) {
                capeId = l.cape();
                accessoryIds = l.accessories();
            }
        }

        // Settle what to show first (cheap), and only decode when it changed.
        CapeRegistry.CapeEntry entry = CapeRegistry.cape(capeId);
        MinecraftCapesProvider.Profile mcc = null;
        if (entry == null) {
            if (capeId >= 0) LOG.debug("Unknown registry cape {} for {} (older jar?)", capeId, uuid);
            if (cfg.minecraftCapes && (local || cfg.showOthers)) {
                MinecraftCapesProvider.Profile p = MinecraftCapesProvider.fetch(uuid, name);
                if (p != null && !p.isEmpty()) mcc = p;
            }
        }
        String signature = entry != null ? "dusk:" + capeId + accessoryIds
                : mcc != null ? "mcc:" + crc(mcc.cape()) + "/" + crc(mcc.ears()) + "/" + mcc.glint() + mcc.upsideDown() + accessoryIds
                : "none:" + accessoryIds;
        if (signature.equals(previous)) return new Resolved(signature, null);

        List<Accessory> accessories = loadAccessories(accessoryIds, key);
        if (entry != null) {
            byte[] png = CapeRegistry.texture(capeId, "cape.png");
            CapeTexture cape = png == null ? null : CapeTexture.prepareCape(key, png, entry.frameMs());
            if (cape == null) {
                LOG.warn("Registry cape {} has no usable cape.png", capeId);
            } else {
                CapeTexture ears = null;
                if (entry.ears()) {
                    byte[] earPng = CapeRegistry.texture(capeId, "ears.png");
                    ears = earPng == null ? null : CapeTexture.prepareEars(key, earPng);
                }
                return new Resolved(signature, PlayerCosmetics.of(cape, ears, entry.glint(), entry.upsideDown(), accessories));
            }
        } else if (mcc != null) {
            CapeTexture cape = mcc.cape() == null ? null : CapeTexture.prepareCape(key, mcc.cape());
            CapeTexture ears = mcc.ears() == null ? null : CapeTexture.prepareEars(key, mcc.ears());
            return new Resolved(signature, PlayerCosmetics.of(cape, ears, cape != null && mcc.glint(), mcc.upsideDown(), accessories));
        }
        return new Resolved(signature, PlayerCosmetics.of(null, null, false, false, accessories));
    }

    private static long crc(byte @Nullable [] bytes) {
        if (bytes == null) return -1;
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    private static List<Accessory> loadAccessories(List<Integer> ids, String key) {
        List<Accessory> out = new ArrayList<>();
        for (int id : ids) {
            CapeRegistry.AccessoryEntry entry = CapeRegistry.accessory(id);
            if (entry == null) continue;
            byte[] modelJson = CapeRegistry.accessoryFile(id, "model.json");
            byte[] png = CapeRegistry.accessoryFile(id, "texture.png");
            if (modelJson == null || png == null) {
                LOG.warn("Registry accessory {} is missing model.json or texture.png", id);
                continue;
            }
            try {
                AccessoryModel model = AccessoryModel.parse(new String(modelJson, StandardCharsets.UTF_8));
                CapeTexture tex = CapeTexture.prepareFrames(key + "/" + id, png, entry.frames(), entry.ticksPerFrame() * 50);
                if (tex == null) {
                    LOG.warn("Registry accessory {} has an unreadable texture", id);
                    continue;
                }
                out.add(new Accessory(entry, model, tex));
            } catch (Exception e) {
                LOG.warn("Registry accessory {} failed to load", id, e);
            }
        }
        return out;
    }
}
