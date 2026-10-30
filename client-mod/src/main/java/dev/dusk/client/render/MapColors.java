package dev.dusk.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What a block looks like from above: the average colour of its top texture
 * (Pl3xMap's table, see NOTICE), mixed with the biome's colour for grass and
 * leaves the way Pl3xMap does, and the vanilla map colour for blocks the
 * table doesn't know (modded ones).
 */
public final class MapColors {
    /** A block the map looks through: plants, glass, torches, buttons. */
    public static final int CLEAR = -1;

    private static final int PLAIN = 0, GRASS = 1, FOLIAGE = 2;
    private static final Set<String> GRASS_BLOCKS = Set.of("grass_block", "fern", "large_fern", "potted_fern", "sugar_cane");
    private static final Set<String> FOLIAGE_BLOCKS = Set.of("oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "vine");
    private static final Map<String, Integer> TABLE = load();

    /** Per block state: rgb (or CLEAR) in the low bits, the tint kind above them. */
    private final Map<BlockState, Long> known = new IdentityHashMap<>();

    /** The block's colour at {@code pos}, opaque rgb, or {@link #CLEAR}. */
    public int color(BlockState state, ClientLevel level, BlockPos pos) {
        Long entry = known.get(state);
        if (entry == null) known.put(state, entry = lookup(state));
        int rgb = (int) (long) entry, tint = (int) (entry >>> 32);
        if (rgb == CLEAR || tint == PLAIN) return rgb;
        int biome = tint == GRASS ? BiomeColors.getAverageGrassColor(level, pos) : BiomeColors.getAverageFoliageColor(level, pos);
        return mix(rgb, biome, 0.5f);
    }

    public static int water(ClientLevel level, BlockPos pos) {
        return BiomeColors.getAverageWaterColor(level, pos) & 0xFFFFFF;
    }

    private static long lookup(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String path = id.startsWith("minecraft:") ? id.substring(10) : null;
        Integer rgb = path == null ? null : TABLE.get(path);
        int color;
        if (rgb != null) {
            color = rgb == 0 ? CLEAR : rgb;
        } else {
            // vanilla's map colour only depends on the state; modded blocks get an empty world to look at
            MapColor m = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            color = m == MapColor.NONE ? CLEAR : m.col;
        }
        long tint = path != null && GRASS_BLOCKS.contains(path) ? GRASS : path != null && FOLIAGE_BLOCKS.contains(path) ? FOLIAGE : PLAIN;
        return tint << 32 | (color & 0xFFFFFFFFL);
    }

    private static Map<String, Integer> load() {
        Map<String, Integer> map = new HashMap<>();
        try (InputStream in = MapColors.class.getResourceAsStream("/assets/duskclient/minimap/block_colors.txt")) {
            if (in == null) return map;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                int sp = line.indexOf(' ');
                if (line.startsWith("#") || sp < 0) continue;
                map.put(line.substring(0, sp), Integer.parseInt(line.substring(sp + 1).trim(), 16));
            }
        } catch (Exception ignored) {
            // no table: everything falls back to map colours
        }
        return map;
    }

    /** {@code a} toward {@code b} by {@code t}, as rgb without alpha. */
    public static int mix(int a, int b, float t) {
        int r = (int) ((a >> 16 & 0xFF) + ((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t);
        int g = (int) ((a >> 8 & 0xFF) + ((b >> 8 & 0xFF) - (a >> 8 & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return r << 16 | g << 8 | bl;
    }

    /** {@code rgb} brightened (f > 1) or darkened (f < 1), opaque. */
    public static int shade(int rgb, float f) {
        int r = Math.min(255, (int) ((rgb >> 16 & 0xFF) * f));
        int g = Math.min(255, (int) ((rgb >> 8 & 0xFF) * f));
        int b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
