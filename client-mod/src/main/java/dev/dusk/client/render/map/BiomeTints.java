package dev.dusk.client.render.map;

import java.util.Arrays;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;

/** Blends a chunk's grass, foliage and water colours the way the game does. Ported from Diorama (MIT, see NOTICE). */
final class BiomeTints {
    private static final ColorResolver[] RESOLVERS = {
        BiomeColors.GRASS_COLOR_RESOLVER,
        BiomeColors.FOLIAGE_COLOR_RESOLVER,
        MapCompat.dryFoliage(),
        BiomeColors.WATER_COLOR_RESOLVER,
    };
    private static final int WATER = 3;

    private BiomeTints() {
    }

    /** The colour of water: what its own tint code asks for, which a mod can make its own. */
    static ColorResolver water() {
        return RESOLVERS[WATER];
    }

    static void setWater(ColorResolver resolver) {
        RESOLVERS[WATER] = resolver;
    }

    /** The snapshot's tints, blended now if no bake has needed them yet. Two bakes may both blend; either result will do. */
    static ChunkSnapshot.Tints of(ChunkSnapshot snapshot) {
        ChunkSnapshot.Tints tints = snapshot.tints;
        if (tints == null) {
            tints = blend(snapshot);
            snapshot.tints = tints;
        }
        return tints;
    }

    private static ChunkSnapshot.Tints blend(ChunkSnapshot snapshot) {
        int[][] colors = new int[RESOLVERS.length][256];
        int radius = snapshot.blendRadius;
        int[] red = new int[RESOLVERS.length];
        int[] green = new int[RESOLVERS.length];
        int[] blue = new int[RESOLVERS.length];
        // Neighbouring columns share most of the blocks they blend, so each is worked out once per height.
        int size = 16 + radius * 2;
        int[] heights = new int[size * size];
        Arrays.fill(heights, Integer.MIN_VALUE);
        int[][] sampled = new int[RESOLVERS.length][size * size];
        boolean[] known = new boolean[size * size];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int bx = (snapshot.chunkX << 4) + x;
                int bz = (snapshot.chunkZ << 4) + z;
                int y = snapshot.surface(x, z);
                Arrays.fill(red, 0);
                Arrays.fill(green, 0);
                Arrays.fill(blue, 0);
                int count = 0;
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int cell = (z + dz + radius) * size + x + dx + radius;
                        if (heights[cell] != y) {
                            heights[cell] = y;
                            Holder<Biome> biome = snapshot.knownBiome((bx + dx) >> 2, y >> 2, (bz + dz) >> 2);
                            known[cell] = biome != null;
                            for (int r = 0; biome != null && r < RESOLVERS.length; r++) {
                                sampled[r][cell] = RESOLVERS[r].getColor(biome.value(), bx + dx, bz + dz);
                            }
                        }
                        if (known[cell]) {
                            count++;
                            for (int r = 0; r < RESOLVERS.length; r++) {
                                int color = sampled[r][cell];
                                red[r] += color >> 16 & 0xFF;
                                green[r] += color >> 8 & 0xFF;
                                blue[r] += color & 0xFF;
                            }
                        }
                    }
                }
                int i = z << 4 | x;
                for (int r = 0; r < RESOLVERS.length; r++) {
                    colors[r][i] = count == 0 ? 0xFFFFFF : (red[r] / count) << 16 | (green[r] / count) << 8 | blue[r] / count;
                }
            }
        }
        return new ChunkSnapshot.Tints(colors[0], colors[1], colors[2], colors[WATER]);
    }
}
