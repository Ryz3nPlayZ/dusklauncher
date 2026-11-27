package dev.dusk.client.render.map;

import dev.dusk.client.DuskClient;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.render.MapTexture;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * The tilted map's moving parts: follows the loaded chunks around the player,
 * copies them for the bake threads, bakes the tiles in view and uploads the
 * results. Everything but the bake jobs runs on the client thread.
 */
public final class MapTiles {
    private static final int BAKERS = 1;
    private static final int SNAPSHOTS_PER_TICK = 2;
    private static final int UPLOADS_PER_TICK = 2;
    private static final int POLL_EVERY_TICKS = 4;
    /** A new chunk's light arrives just after its blocks. */
    private static final int NEW_CHUNK_WAIT = 15;
    private static final int EDIT_WAIT = 5;
    /** A busy redstone machine can't copy its chunk more often than this. */
    private static final int EDIT_GAP = 40;
    private static final int MAX_CHUNK_RADIUS = 12;
    private static final int TRACE_MARGIN = 40;
    /** How far hills can shift a column up or down the map. */
    private static final int HEIGHT_SHIFT = 33;
    private static final long REBAKE_GAP_MS = 750;
    /** A tile that already shows something waits this long between refreshes. */
    private static final long REFRESH_GAP_MS = 4000;
    /** How long the old cave slice stays while the next one bakes. */
    private static final long LAYER_HOLD_MS = 3000;
    /** How many blocks of rock above the player count as underground; a house roof is thinner. */
    private static final int MIN_CAVE_DEPTH = 8;
    private static final int MAX_SKY_DEPTH = 32;

    private static volatile MapTiles active;

    private final ExecutorService bakers = Executors.newFixedThreadPool(BAKERS, job -> {
        Thread thread = new Thread(job, "Dusk Minimap");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final Queue<Baked> done = new ConcurrentLinkedQueue<>();
    private final Set<Long> edited = ConcurrentHashMap.newKeySet();
    private final Map<Long, Tracked> tracked = new HashMap<>();
    private final Map<TileKey, Tile> tiles = new HashMap<>();
    private final ArrayDeque<MapTexture> freeTextures = new ArrayDeque<>();
    private final List<MapTexture> allTextures = new ArrayList<>();
    private ClientLevel level;
    private MapChunks chunks;
    private BlockLooks looks;
    private int ticks;
    private int inFlight;
    private boolean underground;
    private boolean showing;
    private int shownLayer;
    private long holdStart = -1;
    private int viewU0;
    private int viewU1;
    private int viewV0;
    private int viewV1;
    private boolean bakeFailed;

    private record Baked(Tile tile, int[] pixels, int version) {
    }

    private static final class Tile {
        final TileKey key;
        MapTexture texture;
        int version = 1;
        int bakedVersion;
        boolean baking;
        long lastBake;

        Tile(TileKey key) {
            this.key = key;
        }
    }

    private static final class Tracked {
        LevelChunk chunk;
        /** The tick to copy this chunk on, or -1 if the copy is current. */
        int copyAt;
        int copiedAt = Integer.MIN_VALUE / 2;

        Tracked(LevelChunk chunk, int copyAt) {
            this.chunk = chunk;
            this.copyAt = copyAt;
        }
    }

    public MapTiles() {
        active = this;
    }

    /** A block changed somewhere in the world. Any thread. */
    public static void blockChanged(int x, int z) {
        MapTiles tiles = active;
        if (tiles != null) {
            tiles.edited.add(ChunkSnapshot.key(x >> 4, z >> 4));
        }
    }

    public void tick(ClientLevel level, Entity viewer, int range) {
        ticks++;
        if (level != this.level) {
            enter(level);
        }
        BlockLooks fresh = BlockLooks.current();
        if (fresh != looks) {
            looks = fresh;
            for (Tile tile : tiles.values()) {
                tile.version++;
            }
        }
        int chunkX = Mth.floor(viewer.getX()) >> 4;
        int chunkZ = Mth.floor(viewer.getZ()) >> 4;
        if (ticks % POLL_EVERY_TICKS == 0) {
            pollChunks(chunkX, chunkZ, chunkRadius(range));
        }
        takeEdits();
        copyChunks(chunkX, chunkZ);

        double u = viewer.getX();
        double v = MapProjection.v(viewer.getY(), viewer.getZ());
        int wanted = autoLayer(viewer);
        keepTiles(u, v, range, wanted);
        pickLayer(wanted);
        finishBakes();
        startBakes(u, v);
    }

    /** Draws the slice on show so that world point {@code (u, v)} lands on {@code (x, y)}. */
    public void draw(Canvas c, float x, float y, double u, double v, float pixelsPerBlock) {
        int originU = Mth.floor(u);
        int originV = Mth.floor(v);
        int scale = MapProjection.PIXELS_PER_UNIT;
        float perTexel = pixelsPerBlock / scale;
        c.push();
        c.translate(x, y);
        c.scale(perTexel, perTexel);
        c.translate((float) (originU - u) * scale, (float) (originV - v) * scale);
        int size = MapProjection.TILE_PIXELS;
        for (Tile tile : tiles.values()) {
            if (tile.texture != null && tile.key.layer() == shownLayer) {
                int tx = (tile.key.minU() - originU) * scale;
                int ty = (tile.key.minV() - originV) * scale;
                c.blit(tile.texture.id(), tx, ty, 0, 0, size, size, size, size);
            }
        }
        c.pop();
    }

    public void close() {
        if (active == this) {
            active = null;
        }
        bakers.shutdownNow();
        for (MapTexture texture : allTextures) {
            texture.close();
        }
    }

    private void enter(ClientLevel level) {
        this.level = level;
        chunks = new MapChunks(level);
        tracked.clear();
        edited.clear();
        for (Tile tile : tiles.values()) {
            free(tile);
        }
        tiles.clear();
        underground = false;
        showing = false;
        holdStart = -1;
    }

    private int chunkRadius(int range) {
        double half = range / 2.0;
        int needed = (int) Math.ceil(Math.max(half + TRACE_MARGIN, half / MapProjection.SIN + HEIGHT_SHIFT + TRACE_MARGIN) / 16);
        return Math.min(needed, Math.min(Minecraft.getInstance().options.getEffectiveRenderDistance() + 1, MAX_CHUNK_RADIUS));
    }

    /** Notices chunks arriving and being replaced, and forgets those left far behind. */
    private void pollChunks(int centerX, int centerZ, int radius) {
        for (int cz = centerZ - radius; cz <= centerZ + radius; cz++) {
            for (int cx = centerX - radius; cx <= centerX + radius; cx++) {
                ChunkAccess access = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(access instanceof LevelChunk chunk)) {
                    continue;
                }
                long key = ChunkSnapshot.key(cx, cz);
                Tracked known = tracked.get(key);
                if (known == null) {
                    tracked.put(key, new Tracked(chunk, ticks + NEW_CHUNK_WAIT));
                } else if (known.chunk != chunk) {
                    known.chunk = chunk;
                    known.copyAt = ticks + NEW_CHUNK_WAIT;
                }
            }
        }
        int keep = radius + 2;
        Iterator<Map.Entry<Long, Tracked>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Tracked> entry = it.next();
            int cx = ChunkSnapshot.x(entry.getKey());
            int cz = ChunkSnapshot.z(entry.getKey());
            if (Math.abs(cx - centerX) > keep || Math.abs(cz - centerZ) > keep) {
                it.remove();
                chunks.chunks.remove(entry.getKey());
            }
        }
    }

    private void takeEdits() {
        if (edited.isEmpty()) {
            return;
        }
        Iterator<Long> it = edited.iterator();
        while (it.hasNext()) {
            Tracked known = tracked.get(it.next());
            it.remove();
            if (known != null && known.copyAt < 0) {
                known.copyAt = Math.max(ticks + EDIT_WAIT, known.copiedAt + EDIT_GAP);
            }
        }
    }

    private void copyChunks(int centerX, int centerZ) {
        for (int n = 0; n < SNAPSHOTS_PER_TICK; n++) {
            long best = 0;
            Tracked next = null;
            int nearest = Integer.MAX_VALUE;
            for (Map.Entry<Long, Tracked> entry : tracked.entrySet()) {
                Tracked known = entry.getValue();
                if (known.copyAt >= 0 && known.copyAt <= ticks) {
                    int dx = ChunkSnapshot.x(entry.getKey()) - centerX;
                    int dz = ChunkSnapshot.z(entry.getKey()) - centerZ;
                    int distance = dx * dx + dz * dz;
                    if (distance < nearest) {
                        nearest = distance;
                        next = known;
                        best = entry.getKey();
                    }
                }
            }
            if (next == null) {
                return;
            }
            next.copyAt = -1;
            next.copiedAt = ticks;
            ChunkSnapshot copy = new ChunkSnapshot(level, next.chunk, ChunkSnapshot.x(best), ChunkSnapshot.z(best));
            chunks.chunks.put(best, copy);
            markTiles(copy);
        }
    }

    /** Every tile this chunk can show in, or cast light or shadow on. */
    private void markTiles(ChunkSnapshot copy) {
        int uLow = copy.chunkX * 16 - TileBaker.SKY_REACH - 1;
        int uHigh = copy.chunkX * 16 + 16 + Math.max(TileBaker.SHADOW_REACH_X, TileBaker.SKY_REACH) + 1;
        int zLow = copy.chunkZ * 16 - Math.max(TileBaker.SHADOW_REACH_Z, TileBaker.SKY_REACH) - 1;
        int zHigh = copy.chunkZ * 16 + 16 + TileBaker.SKY_REACH + 1;
        int yHigh = Math.max(copy.maxY, chunks.minY) + 1;
        double vLow = MapProjection.v(yHigh, zLow);
        double vHigh = MapProjection.v(copy.floorY - TileBaker.SHADOW_REACH_Y, zHigh);
        double caveVHigh = MapProjection.v(chunks.minY, zHigh);
        for (Tile tile : tiles.values()) {
            int minU = tile.key.minU();
            int minV = tile.key.minV();
            double bottom = tile.key.isCave() ? caveVHigh : vHigh;
            if (minU < uHigh && minU + MapProjection.TILE_UNITS > uLow && minV < bottom && minV + MapProjection.TILE_UNITS > vLow) {
                tile.version++;
            }
        }
    }

    private int autoLayer(Entity viewer) {
        BlockPos eye = BlockPos.containing(viewer.getEyePosition());
        if (level.dimensionType().hasCeiling()) {
            underground = true;
        } else {
            int sky = level.getBrightness(LightLayer.SKY, eye);
            // Separate thresholds so walking along a cave mouth doesn't flip the map back and forth.
            underground = !level.canSeeSky(eye) && sky < (underground ? 10 : 5);
        }
        // A roof overhead isn't the same as being in a cave.
        if (!underground || depthToSky(eye) < MIN_CAVE_DEPTH) {
            return TileKey.SURFACE;
        }
        return Math.floorDiv(Mth.floor(viewer.getY()) + 5, 4) * 4;
    }

    /** Blocks between the eye and the first place open to the sky, or more than {@link #MAX_SKY_DEPTH}. */
    private int depthToSky(BlockPos eye) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int top = Math.min(chunks.maxY - 1, eye.getY() + MAX_SKY_DEPTH);
        for (int y = eye.getY(); y <= top; y++) {
            if (level.getBrightness(LightLayer.SKY, pos.set(eye.getX(), y, eye.getZ())) >= 15) {
                return y - eye.getY();
            }
        }
        return MAX_SKY_DEPTH + 1;
    }

    /** Makes the tiles in view exist, and lets go of those well outside it. */
    private void keepTiles(double u, double v, int range, int wanted) {
        double half = range / 2.0;
        viewU0 = Math.floorDiv(Mth.floor(u - half), MapProjection.TILE_UNITS);
        viewU1 = Math.floorDiv(Mth.floor(u + half), MapProjection.TILE_UNITS);
        viewV0 = Math.floorDiv(Mth.floor(v - half), MapProjection.TILE_UNITS);
        viewV1 = Math.floorDiv(Mth.floor(v + half), MapProjection.TILE_UNITS);
        Iterator<Tile> it = tiles.values().iterator();
        while (it.hasNext()) {
            Tile tile = it.next();
            TileKey key = tile.key;
            boolean layerInUse = key.layer() == wanted || showing && key.layer() == shownLayer;
            if (!layerInUse || key.tileU() < viewU0 - 2 || key.tileU() > viewU1 + 2 || key.tileV() < viewV0 - 2 || key.tileV() > viewV1 + 2) {
                free(tile);
                it.remove();
            }
        }
        for (int tv = viewV0 - 1; tv <= viewV1 + 1; tv++) {
            for (int tu = viewU0 - 1; tu <= viewU1 + 1; tu++) {
                TileKey key = new TileKey(wanted, tu, tv);
                tiles.computeIfAbsent(key, Tile::new);
            }
        }
    }

    /** The slice the player is in once it has baked, the one on screen until then. */
    private void pickLayer(int wanted) {
        long now = System.currentTimeMillis();
        if (!showing || wanted == shownLayer) {
            showing = true;
            shownLayer = wanted;
            holdStart = -1;
            return;
        }
        if (holdStart < 0) {
            holdStart = now;
        }
        if (now - holdStart > LAYER_HOLD_MS || ready(wanted) || !showsAnything(shownLayer)) {
            shownLayer = wanted;
            holdStart = -1;
        }
    }

    private boolean ready(int layer) {
        for (int tv = viewV0; tv <= viewV1; tv++) {
            for (int tu = viewU0; tu <= viewU1; tu++) {
                Tile tile = tiles.get(new TileKey(layer, tu, tv));
                if (tile == null || tile.texture == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean showsAnything(int layer) {
        for (Tile tile : tiles.values()) {
            if (tile.key.layer() == layer && tile.texture != null) {
                return true;
            }
        }
        return false;
    }

    private void finishBakes() {
        for (int n = 0; n < UPLOADS_PER_TICK; ) {
            Baked baked = done.poll();
            if (baked == null) {
                return;
            }
            inFlight--;
            Tile tile = baked.tile;
            tile.baking = false;
            if (tiles.get(tile.key) != tile) {
                continue;
            }
            tile.bakedVersion = baked.version;
            if (baked.pixels != null) {
                upload(tile, baked.pixels);
                n++;
            }
        }
    }

    private void upload(Tile tile, int[] pixels) {
        if (tile.texture == null) {
            tile.texture = freeTextures.poll();
        }
        if (tile.texture == null) {
            tile.texture = new MapTexture("duskclient:minimap/" + allTextures.size(), MapProjection.TILE_PIXELS);
            allTextures.add(tile.texture);
        }
        int size = MapProjection.TILE_PIXELS;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                tile.texture.set(x, y, pixels[y * size + x]);
            }
        }
        tile.texture.upload();
    }

    private void free(Tile tile) {
        if (tile.texture != null) {
            freeTextures.add(tile.texture);
            tile.texture = null;
        }
    }

    private void startBakes(double u, double v) {
        if (inFlight > BAKERS || Minecraft.getInstance().isPaused()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Tile> stale = new ArrayList<>();
        for (Tile tile : tiles.values()) {
            if (tile.baking || tile.bakedVersion == tile.version) {
                continue;
            }
            // Holes fill fast; a tile already on screen refreshes now and then, and only while in view.
            boolean hole = tile.texture == null;
            if (hole ? now - tile.lastBake >= REBAKE_GAP_MS : inView(tile.key) && now - tile.lastBake >= REFRESH_GAP_MS) {
                stale.add(tile);
            }
        }
        // Holes first: nearby tiles that keep changing mustn't leave the far ones blank.
        stale.sort(Comparator.<Tile, Boolean>comparing(tile -> tile.texture != null).thenComparingDouble(tile -> distance(tile.key, u, v)));
        for (Tile tile : stale) {
            if (inFlight > BAKERS) {
                return;
            }
            inFlight++;
            tile.baking = true;
            tile.lastBake = now;
            bake(tile);
        }
    }

    private boolean inView(TileKey key) {
        return key.tileU() >= viewU0 && key.tileU() <= viewU1 && key.tileV() >= viewV0 && key.tileV() <= viewV1;
    }

    private static double distance(TileKey key, double u, double v) {
        double du = key.minU() + MapProjection.TILE_UNITS / 2.0 - u;
        double dv = key.minV() + MapProjection.TILE_UNITS / 2.0 - v;
        return du * du + dv * dv;
    }

    private void bake(Tile tile) {
        int version = tile.version;
        MapChunks source = chunks;
        BlockLooks blocks = looks;
        boolean refresh = tile.texture != null;
        bakers.execute(() -> {
            int[] pixels = null;
            long start = System.nanoTime();
            try {
                pixels = new TileBaker(tile.key, source, blocks).bake();
            } catch (RuntimeException e) {
                if (!bakeFailed) {
                    bakeFailed = true;
                    DuskClient.LOGGER.warn("Minimap tile failed to bake", e);
                }
            }
            done.add(new Baked(tile, pixels, version));
            if (refresh) {
                // Refreshes use at most half a core, so the map never eats the frame budget on a laptop.
                try {
                    Thread.sleep((System.nanoTime() - start) / 1_000_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }
}
