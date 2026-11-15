package dev.dusk.client.render.map;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.multiplayer.ClientLevel;

/** The chunks the map knows in one world. The client thread writes; bake threads read. */
final class MapChunks {
    static final int NO_ROOF = Integer.MIN_VALUE;

    final int minY;
    /** One above the highest block the world allows. */
    final int maxY;
    /** Where a ceiling dimension's roof starts, or {@link #NO_ROOF}. */
    final int roof;
    final Map<Long, ChunkSnapshot> chunks = new ConcurrentHashMap<>();

    MapChunks(ClientLevel level) {
        minY = MapCompat.minY(level);
        maxY = MapCompat.maxY(level);
        roof = level.dimensionType().hasCeiling() ? minY + level.dimensionType().logicalHeight() : NO_ROOF;
    }

    ChunkSnapshot chunk(int chunkX, int chunkZ) {
        return chunks.get(ChunkSnapshot.key(chunkX, chunkZ));
    }
}
