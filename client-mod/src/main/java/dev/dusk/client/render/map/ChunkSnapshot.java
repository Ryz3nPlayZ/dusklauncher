package dev.dusk.client.render.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.block.state.BlockState;

/** A copy of a chunk taken on the client thread, for the bake threads to read. */
final class ChunkSnapshot {
    /** A column whose lowest top is above this is a tall mod structure, not ground to start from. */
    private static final int ORDINARY_TOP = 320;

    final int chunkX;
    final int chunkZ;
    final int minSectionY;
    final int blendRadius;
    /** The highest block in the chunk, or {@code Integer.MIN_VALUE} if it's empty. */
    final int maxY;
    /** Nothing below this can be seen from above, roughly. */
    final int floorY;
    final PalettedContainer<BlockState>[] states;
    private final boolean hasSkyLight;
    private final DataLayer[] blockLight;
    private final DataLayer[] skyLight;
    private final int[] surface = new int[256];
    private final PalettedContainerRO<Holder<Biome>>[][] biomes;
    volatile Tints tints;

    record Tints(int[] grass, int[] foliage, int[] dryFoliage, int[] water) {
    }

    @SuppressWarnings("unchecked")
    ChunkSnapshot(ClientLevel level, LevelChunk chunk, int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        minSectionY = MapCompat.minSectionY(level);
        blendRadius = Minecraft.getInstance().options.biomeBlendRadius().get();
        hasSkyLight = level.dimensionType().hasSkyLight();
        LevelChunkSection[] sections = chunk.getSections();
        states = new PalettedContainer[sections.length];
        blockLight = new DataLayer[sections.length];
        skyLight = new DataLayer[sections.length];
        LevelLightEngine light = level.getLightEngine();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section != null && !section.hasOnlyAir()) {
                states[i] = section.getStates().copy();
            }
            SectionPos pos = SectionPos.of(chunkX, minSectionY + i, chunkZ);
            DataLayer block = light.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
            DataLayer sky = light.getLayerListener(LightLayer.SKY).getDataLayerData(pos);
            blockLight[i] = block == null ? null : block.copy();
            skyLight[i] = sky == null ? null : sky.copy();
        }

        // Biomes never change, so the neighbours' are held as they are.
        biomes = new PalettedContainerRO[9][];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkAccess near = level.getChunkSource().getChunk(chunkX + dx, chunkZ + dz, ChunkStatus.BIOMES, false);
                if (near == null) {
                    continue;
                }
                LevelChunkSection[] nearSections = near.getSections();
                PalettedContainerRO<Holder<Biome>>[] held = new PalettedContainerRO[nearSections.length];
                for (int i = 0; i < nearSections.length; i++) {
                    held[i] = nearSections[i] == null ? null : nearSections[i].getBiomes();
                }
                biomes[(dx + 1) * 3 + dz + 1] = held;
            }
        }

        int minY = MapCompat.minY(level);
        int top = Integer.MIN_VALUE;
        int floor = Integer.MAX_VALUE;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                top = Math.max(top, height);
                floor = Math.min(floor, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
                surface[z << 4 | x] = height;
            }
        }
        maxY = top <= minY ? Integer.MIN_VALUE : top;
        floorY = floor == Integer.MAX_VALUE || floor > ORDINARY_TOP ? minY : floor - 24;
    }

    static int x(long key) {
        return (int) (key >> 32);
    }

    static int z(long key) {
        return (int) key;
    }

    static long key(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
    }

    int surface(int x, int z) {
        return surface[z << 4 | x];
    }

    /** One above the tallest block below {@code ceiling} as far as the column tops tell, or {@code Integer.MIN_VALUE}. */
    int topBelow(int ceiling) {
        if (maxY < ceiling) {
            return maxY == Integer.MIN_VALUE ? Integer.MIN_VALUE : maxY + 1;
        }
        int top = Integer.MIN_VALUE;
        for (int y : surface) {
            if (y < ceiling) {
                top = Math.max(top, y + 1);
            }
        }
        return top;
    }

    int blockLight(int section, int x, int y, int z) {
        DataLayer layer = blockLight[section];
        return layer == null ? 0 : layer.get(x, y, z);
    }

    int skyLight(int section, int x, int y, int z) {
        if (!hasSkyLight) {
            return 0;
        }
        DataLayer layer = skyLight[section];
        if (layer != null) {
            return layer.get(x, y, z);
        }
        // Same as the game: a section with none stored takes the bottom of the nearest stored one above.
        for (int i = section + 1; i < skyLight.length; i++) {
            if (skyLight[i] != null) {
                return skyLight[i].get(x, 0, z);
            }
        }
        return 15;
    }

    /** The biome at a quart (4-block) position in this chunk or one next to it, or null if the game had no chunk there. */
    Holder<Biome> knownBiome(int quartX, int quartY, int quartZ) {
        int dx = (quartX >> 2) - chunkX;
        int dz = (quartZ >> 2) - chunkZ;
        PalettedContainerRO<Holder<Biome>>[] sections = dx < -1 || dx > 1 || dz < -1 || dz > 1 ? null : biomes[(dx + 1) * 3 + dz + 1];
        if (sections == null) {
            return null;
        }
        int minQuart = minSectionY << 2;
        int y = Mth.clamp(quartY, minQuart, minQuart + (sections.length << 2) - 1);
        PalettedContainerRO<Holder<Biome>> section = sections[(y >> 2) - minSectionY];
        return section == null ? null : section.get(quartX & 3, y & 3, quartZ & 3);
    }
}
