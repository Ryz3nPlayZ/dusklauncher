package dev.dusk.client.render.skin;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Built voxel meshes per skin texture (and arm width). A skin whose pixels
 * aren't there yet (still downloading) is asked for again a second later;
 * one that can't be read at all is remembered as flat.
 */
public final class SkinVoxelCache {
    private SkinVoxelCache() {}

    /** Returned by a loader for a texture that will never have readable pixels. */
    public static final int[] UNREADABLE = new int[0];

    private static final int MAX = 128;
    private static final long RETRY_NANOS = 1_000_000_000L;
    private static final SkinVoxels NONE = SkinVoxels.build(new int[64 * 64], false);

    private record Key(Object texture, boolean slim) {}

    private static final Map<Key, SkinVoxels> MESHES = new LinkedHashMap<>(32, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, SkinVoxels> eldest) {
            return size() > MAX;
        }
    };
    private static final Map<Object, Long> RETRY_AT = new HashMap<>();

    // Each player's mesh is asked for several times a frame; skip the map for a repeat.
    private static Object lastTexture;
    private static boolean lastSlim;
    private static SkinVoxels lastMesh;

    /**
     * The voxels for {@code texture}, or null when it can't be drawn in 3D
     * (yet). {@code loader} gives the 64×64 alpha, null while not loaded, or
     * {@link #UNREADABLE}. Render thread only.
     */
    @Nullable
    public static SkinVoxels get(Object texture, boolean slim, Function<Object, int[]> loader) {
        if (texture == lastTexture && slim == lastSlim && lastMesh != null) return lastMesh == NONE ? null : lastMesh;
        SkinVoxels found = lookup(texture, slim, loader);
        if (found != null) {
            lastTexture = texture;
            lastSlim = slim;
            lastMesh = found;
        }
        return found == NONE ? null : found;
    }

    /** The cached or freshly built mesh ({@link #NONE} when flat), or null to ask again later. */
    @Nullable
    private static SkinVoxels lookup(Object texture, boolean slim, Function<Object, int[]> loader) {
        Key key = new Key(texture, slim);
        SkinVoxels mesh = MESHES.get(key);
        if (mesh != null) return mesh;
        Long retry = RETRY_AT.get(texture);
        long now = System.nanoTime();
        if (retry != null && now < retry) return null;
        int[] alpha;
        try {
            alpha = loader.apply(texture);
        } catch (RuntimeException e) {
            alpha = UNREADABLE;
        }
        if (alpha == null) {
            if (RETRY_AT.size() > MAX) RETRY_AT.clear();
            RETRY_AT.put(texture, now + RETRY_NANOS);
            return null;
        }
        RETRY_AT.remove(texture);
        mesh = alpha.length == 64 * 64 ? SkinVoxels.build(alpha, slim) : NONE;
        MESHES.put(key, mesh);
        return mesh;
    }

    /** The alpha channel of a 64×64 skin; {@code pixel} returns ABGR or ARGB (alpha is the top byte in both). */
    public static int[] alphaOf(int width, int height, java.util.function.IntBinaryOperator pixel) {
        if (width != 64 || height != 64) return UNREADABLE;
        int[] alpha = new int[64 * 64];
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) alpha[y * 64 + x] = pixel.applyAsInt(x, y) >>> 24;
        }
        return alpha;
    }

    public static void clear() {
        MESHES.clear();
        RETRY_AT.clear();
        lastTexture = null;
        lastMesh = null;
    }
}
