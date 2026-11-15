package dev.dusk.client.render.map;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fast block lookups for one bake: each chunk section it touches is unpacked once into flat arrays.
 * Ported from Diorama (MIT, see NOTICE), without its packed-palette path.
 */
final class VoxelReader {
    /** The chunk isn't known. */
    static final int UNKNOWN = -1;
    static final int AIR_ID = BlockLooks.id(Blocks.AIR.defaultBlockState());
    /** How many blocks under a lid count as its underside. */
    private static final int CEILING_UNDERSIDE = 2;
    private static final int TABLE_BITS = 5;
    private static final int TABLE_MASK = (1 << TABLE_BITS) - 1;

    private static final class Section {
        final int[] ids = new int[4096];
        final byte[] solid = new byte[4096];
    }

    private static final Section MISSING = filled(UNKNOWN, UNKNOWN);
    private static final Section EMPTY = filled(AIR_ID, 0);

    private static Section filled(int id, int solid) {
        Section section = new Section();
        Arrays.fill(section.ids, id);
        Arrays.fill(section.solid, (byte) solid);
        return section;
    }

    private static final class Chunk {
        final ChunkSnapshot snapshot;
        final Section[] sections;

        Chunk(ChunkSnapshot snapshot) {
            this.snapshot = snapshot;
            sections = snapshot == null ? null : new Section[snapshot.states.length];
        }
    }

    private final MapChunks source;
    private final BlockLooks looks;
    private final int minY;
    private final int maxY;
    private final Long2ObjectOpenHashMap<Chunk> chunks = new Long2ObjectOpenHashMap<>();
    private final int[] tableX = new int[1 << TABLE_BITS * 2];
    private final int[] tableZ = new int[1 << TABLE_BITS * 2];
    private final Chunk[] table = new Chunk[1 << TABLE_BITS * 2];
    private int lastX = Integer.MIN_VALUE;
    private int lastY;
    private int lastZ;
    private Section last;
    private BlockState lastState;
    private int lastStateId;

    VoxelReader(MapChunks source, BlockLooks looks) {
        this.source = source;
        this.looks = looks;
        minY = source.minY;
        maxY = source.maxY;
        Arrays.fill(tableX, Integer.MIN_VALUE);
    }

    private Chunk chunk(int cx, int cz) {
        int slot = (cx & TABLE_MASK) << TABLE_BITS | cz & TABLE_MASK;
        if (tableX[slot] == cx && tableZ[slot] == cz) {
            return table[slot];
        }
        long key = ChunkSnapshot.key(cx, cz);
        Chunk chunk = chunks.get(key);
        if (chunk == null) {
            chunk = new Chunk(source.chunk(cx, cz));
            chunks.put(key, chunk);
        }
        tableX[slot] = cx;
        tableZ[slot] = cz;
        table[slot] = chunk;
        return chunk;
    }

    /** The section holding a block inside the world's height. */
    private Section section(int x, int y, int z) {
        int cx = x >> 4;
        int sy = y >> 4;
        int cz = z >> 4;
        if (cx == lastX && sy == lastY && cz == lastZ) {
            return last;
        }
        Chunk chunk = chunk(cx, cz);
        Section section;
        if (chunk.snapshot == null) {
            section = MISSING;
        } else {
            int index = sy - chunk.snapshot.minSectionY;
            if (index < 0 || index >= chunk.sections.length) {
                section = EMPTY;
            } else {
                section = chunk.sections[index];
                if (section == null) {
                    section = decode(chunk.snapshot, index);
                    chunk.sections[index] = section;
                }
            }
        }
        lastX = cx;
        lastY = sy;
        lastZ = cz;
        last = section;
        return section;
    }

    private Section decode(ChunkSnapshot snapshot, int index) {
        var states = snapshot.states[index];
        if (states == null) {
            return EMPTY;
        }
        Section section = new Section();
        int at = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = states.get(x, y, z);
                    if (state != lastState) {
                        lastState = state;
                        lastStateId = BlockLooks.id(state);
                    }
                    int id = lastStateId;
                    section.ids[at] = id;
                    section.solid[at] = (byte) (looks.kind[id] != BlockLooks.SOLID ? 0 : looks.shape[id] != 0 ? 2 : 1);
                    at++;
                }
            }
        }
        return section;
    }

    private static int index(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | x & 15;
    }

    /** The block's table id, {@link #AIR_ID} out of the world, or {@link #UNKNOWN}. */
    int state(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return AIR_ID;
        }
        return section(x, y, z).ids[index(x, y, z)];
    }

    /** 1 for a full solid cube, 2 for a smaller solid block, 0 for neither, or {@link #UNKNOWN}. */
    int solid(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return 0;
        }
        return section(x, y, z).solid[index(x, y, z)];
    }

    /** An open cell walled in above and below, which the lid over a cave takes for rock. */
    boolean pocket(int x, int y, int z) {
        return solid(x, y + 1, z) > 0 && solid(x, y - 1, z) > 0;
    }

    /** The lowest y of the rock over this column: solid blocks from the cut down to the first open space. */
    int lid(int x, int z, int startY, int limitY) {
        int y = startY - 1;
        while (y >= limitY && (solid(x, y, z) > 0 || y + 1 < startY && pocket(x, y, z))) {
            y--;
        }
        return y + 1;
    }

    boolean ceilingUnderside(int x, int y, int z, int lid, int limitY) {
        return y >= lid && y < lid + CEILING_UNDERSIDE && lid > limitY && solid(x, lid - 1, z) == 0;
    }

    /** Above this y nothing in these chunks can block a ray; chunks not known are left out. */
    int solidTop(int cx0, int cz0, int cx1, int cz1) {
        int top = Integer.MIN_VALUE;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                ChunkSnapshot snapshot = chunk(cx, cz).snapshot;
                if (snapshot != null && snapshot.maxY != Integer.MIN_VALUE) {
                    top = Math.max(top, snapshot.maxY + 1);
                }
            }
        }
        return top;
    }

    int blockLight(int x, int y, int z) {
        ChunkSnapshot snapshot = snapshot(x, z);
        if (snapshot == null || y < minY || y >= maxY) {
            return 0;
        }
        return snapshot.blockLight((y >> 4) - snapshot.minSectionY, x & 15, y & 15, z & 15);
    }

    int skyLight(int x, int y, int z) {
        if (y < minY) {
            return 0;
        }
        ChunkSnapshot snapshot = snapshot(x, z);
        if (snapshot == null || y >= maxY) {
            return 15;
        }
        return snapshot.skyLight((y >> 4) - snapshot.minSectionY, x & 15, y & 15, z & 15);
    }

    ChunkSnapshot snapshot(int x, int z) {
        return chunk(x >> 4, z >> 4).snapshot;
    }
}
