package dev.dusk.client.render.map;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/**
 * Bakes one map tile by tracing a ray per pixel down through a copy of the world. Four rays a pixel,
 * sunlight with soft shadows, corner shading, sky visibility, clear water and outlines. Ported from
 * Diorama's TileBaker (MIT, see NOTICE). One baker is one job on one thread.
 */
final class TileBaker {
    static final int SKY_REACH = 12;
    private static final int SHADOW_STEPS = 96;
    private static final int SHADOW_DISTANCE = 40;
    private static final double SUN_AZIMUTH_X = -0.878;
    private static final double SUN_AZIMUTH_Z = 0.479;
    /** How far a shadow can land from what casts it. */
    static final int SHADOW_REACH_X = (int) Math.ceil(SHADOW_DISTANCE * Math.cos(MapLighting.SUN_ELEVATION) * -SUN_AZIMUTH_X) + 1;
    static final int SHADOW_REACH_Z = (int) Math.ceil(SHADOW_DISTANCE * Math.cos(MapLighting.SUN_ELEVATION) * SUN_AZIMUTH_Z) + 1;
    static final int SHADOW_REACH_Y = (int) Math.ceil(SHADOW_DISTANCE * Math.sin(MapLighting.SUN_ELEVATION)) + 1;
    /** How far below a cave cut the map looks. */
    private static final int CAVE_DEPTH = 48;
    private static final double[][] SAMPLES = {{0.375, 0.125}, {0.875, 0.375}, {0.125, 0.625}, {0.625, 0.875}};
    private static final double[][] SUN_JITTER = {{0.03, 0, -0.02}, {-0.02, 0, 0.03}, {0.02, 0, 0.02}, {-0.03, 0, -0.03}};
    private static final float[] AO_LEVELS = {0.6F, 0.74F, 0.87F, 1.0F};
    private static final float SIDE = 0.82F;
    private static final float OUTLINE_DEPTH = 1.5F;
    private static final float OUTLINE_WATER_FADE = 6;

    // Under a lid (the Nether's roof): a low sun from a fixed side, deep red ambient, and haze far down.
    private static final float LID_AMBIENT = 0.26F;
    private static final float LID_KEY = 0.52F;
    private static final float[] LID_KEY_TINT = {1.1F, 1.0F, 0.88F};
    private static final float[] LID_FILL_TINT = {1.0F, 0.86F, 0.84F};
    private static final float LID_GLOW = 0.7F;
    private static final int HAZE_START = 30;
    private static final int HAZE_DEPTH = 55;
    private static final float HAZE_MAX = 0.72F;
    private static final float HAZE_LIGHT = 1.6F;
    private static final float HAZE_MAX_LUMINANCE = 0.025F;
    private static final int NETHER_WASTES_FOG = 0x330808;

    private static final double[][] SKY_RAYS;
    private static final double[] SKY_WEIGHTS;
    private static final float[] SRGB_TO_LINEAR = new float[256];
    private static final int[] LINEAR_TO_SRGB = new int[4096];

    static {
        int ring = 6;
        double[] elevations = {Math.toRadians(25), Math.toRadians(55)};
        SKY_RAYS = new double[ring * 2 + 1][];
        SKY_WEIGHTS = new double[ring * 2 + 1];
        for (int r = 0; r < 2; r++) {
            double e = elevations[r];
            for (int i = 0; i < ring; i++) {
                double azimuth = (i + r * 0.5) * 2 * Math.PI / ring;
                SKY_RAYS[r * ring + i] = new double[] {Math.cos(e) * Math.cos(azimuth), Math.sin(e), Math.cos(e) * Math.sin(azimuth)};
                SKY_WEIGHTS[r * ring + i] = Math.sin(e);
            }
        }
        SKY_RAYS[ring * 2] = new double[] {0, 1, 0};
        SKY_WEIGHTS[ring * 2] = 1.5;

        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;
            SRGB_TO_LINEAR[i] = (float) (v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i < 4096; i++) {
            double v = i / 4095.0;
            double s = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
            LINEAR_TO_SRGB[i] = (int) Math.round(Math.max(0, Math.min(1, s)) * 255);
        }
    }

    private final TileKey key;
    private final MapChunks source;
    private final BlockLooks looks;
    private final VoxelReader reader;
    private final boolean cave;
    /** Rays start inside rock and fall until they find open space. */
    private final boolean lit;
    /** There's a dimension roof over everything, lit in the Nether's own way. */
    private final boolean lifted;
    private final int roof;
    private final int startY;
    private final int limitY;
    /** Above this no solid block is known, so light can stop looking for one. */
    private final int rayTop;
    /** The lowest chunk top around the tile: a ray over a chunk not loaded yet passes while above this. */
    private final int passY;
    private final double sin = MapProjection.SIN;
    private final double cos = MapProjection.COS;
    private final double invSin = 1 / sin;
    private final double invCos = 1 / cos;
    private final double sunX;
    private final double sunY;
    private final double sunZ;
    private final double depthOrigin;
    private final Long2IntOpenHashMap lidAt = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap fogAt = new Long2IntOpenHashMap();
    private final Long2FloatOpenHashMap skyAtCorner = new Long2FloatOpenHashMap();

    // One ray's results; a baker is single threaded and these would otherwise cost an allocation per ray.
    private float outR;
    private float outG;
    private float outB;
    private float outDepth;
    private float outWater;
    private float albedoR;
    private float albedoG;
    private float albedoB;
    private final float[] light = new float[3];

    TileBaker(TileKey key, MapChunks source, BlockLooks looks) {
        this.key = key;
        this.source = source;
        this.looks = looks;
        reader = new VoxelReader(source, looks);
        cave = key.isCave();
        roof = source.roof;
        boolean roofless = !cave && roof != MapChunks.NO_ROOF;
        lit = cave || roofless;
        lifted = lit && roof != MapChunks.NO_ROOF;
        double e = MapLighting.SUN_ELEVATION;
        sunX = Math.cos(e) * SUN_AZIMUTH_X;
        sunY = Math.sin(e);
        sunZ = Math.cos(e) * SUN_AZIMUTH_Z;
        int[] tops = localTops(key, source, 0);
        rayTop = localTops(key, source, SKY_REACH)[0];
        passY = tops[1];
        startY = cave ? Math.min(key.layer(), source.maxY) : roofless ? Math.min(roof, source.maxY) : tops[0];
        double originZ = (key.minV() + startY * cos) / sin;
        depthOrigin = -(sin * startY + cos * originZ);
        limitY = cave ? Math.max(source.minY, startY - CAVE_DEPTH) : source.minY;
        lidAt.defaultReturnValue(Integer.MIN_VALUE);
        fogAt.defaultReturnValue(-1);
    }

    /** {highest, lowest} top among the chunks whose blocks can show in, or shade, this tile. */
    private static int[] localTops(TileKey key, MapChunks source, int moreNorth) {
        int ceiling = source.maxY;
        double minU = key.minU();
        double minV = key.minV();
        int x0 = Math.floorDiv((int) Math.floor(minU) - 1, 16);
        int x1 = Math.floorDiv((int) Math.ceil(minU + MapProjection.TILE_UNITS) + SHADOW_REACH_X + 1, 16);
        int z0 = Math.floorDiv((int) Math.floor(MapProjection.zAt(minV, source.minY)) - 1 - moreNorth, 16);
        int z1 = Math.floorDiv((int) Math.ceil(MapProjection.zAt(minV + MapProjection.TILE_UNITS, ceiling)) + SHADOW_REACH_Z + 1, 16);
        int top = Integer.MIN_VALUE;
        int low = Integer.MAX_VALUE;
        for (int cx = x0 - 2; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1 + 1; cz++) {
                ChunkSnapshot chunk = source.chunk(cx, cz);
                if (chunk != null && chunk.maxY != Integer.MIN_VALUE) {
                    int chunkTop = chunk.topBelow(ceiling);
                    top = Math.max(top, chunkTop);
                    low = Math.min(low, chunkTop);
                }
            }
        }
        return new int[] {top == Integer.MIN_VALUE ? ceiling : Math.min(ceiling, top), low == Integer.MAX_VALUE ? ceiling : low};
    }

    private int lid(int x, int z) {
        long at = (long) x << 32 | z & 0xFFFFFFFFL;
        int lid = lidAt.get(at);
        if (lid == Integer.MIN_VALUE) {
            lid = reader.lid(x, z, startY, limitY);
            lidAt.put(at, lid);
        }
        return lid;
    }

    /** The tile as ARGB pixels, row by row. A pixel no ray could see is clear. */
    int[] bake() {
        int size = MapProjection.TILE_PIXELS;
        int[] out = new int[size * size];
        if (startY <= limitY) {
            return out;
        }
        // Outlines compare each pixel with its neighbours, so bake a one-pixel border around the tile.
        int w = size + 2;
        int[] pixels = new int[w * w];
        float[] depth = new float[w * w];
        float[] water = new float[w * w];
        double minU = key.minU();
        double minV = key.minV();
        double ppu = MapProjection.PIXELS_PER_UNIT;
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) {
                float r = 0;
                float g = 0;
                float b = 0;
                float d = 0;
                float wd = 0;
                int known = 0;
                for (int s = 0; s < 4; s++) {
                    outWater = 0;
                    if (trace(minU + (x - 1 + SAMPLES[s][0]) / ppu, minV + (y - 1 + SAMPLES[s][1]) / ppu, s)) {
                        r += outR;
                        g += outG;
                        b += outB;
                        d += outDepth;
                        wd += outWater;
                        known++;
                    }
                }
                int i = y * w + x;
                if (known > 0) {
                    pixels[i] = argb(r / known, g / known, b / known, known * 255 / 4);
                    depth[i] = d / known;
                    water[i] = wd / known;
                } else {
                    depth[i] = Float.NaN;
                }
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int i = (y + 1) * w + x + 1;
                int pixel = pixels[i];
                float here = depth[i];
                if (pixel != 0 && (nearer(depth[i - 1], here) || nearer(depth[i + 1], here) || nearer(depth[i - w], here) || nearer(depth[i + w], here))) {
                    pixel = darken(pixel, 1 - MapLighting.OUTLINE * (float) Math.exp(-water[i] / OUTLINE_WATER_FADE));
                }
                out[y * size + x] = pixel;
            }
        }
        return out;
    }

    private static boolean nearer(float neighbour, float here) {
        return here - neighbour > OUTLINE_DEPTH;
    }

    private static int darken(int argb, float keep) {
        int r = Math.round((argb >> 16 & 0xFF) * keep);
        int g = Math.round((argb >> 8 & 0xFF) * keep);
        int b = Math.round((argb & 0xFF) * keep);
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** Follows one ray down from the top of the map; false if it ran into a chunk not known. */
    private boolean trace(double u, double v, int sample) {
        final double sin = this.sin;
        final double cos = this.cos;
        final int x = (int) Math.floor(u);
        final double fx = u - x;
        final double y0 = startY;
        final double z0 = (v + y0 * cos) / sin;

        int by = startY - 1;
        int bz = (int) Math.floor(z0);
        double tMaxY = (y0 - by) / sin;
        double tMaxZ = (z0 - bz) / cos;
        final double tDeltaY = 1.0 / sin;
        final double tDeltaZ = 1.0 / cos;
        double t = 0;
        boolean enteredTop = true;

        boolean skippingRock = lit;
        // Colour picked up from plants and glass on the way down, applied to the surface below.
        float overR = 0;
        float overG = 0;
        float overB = 0;
        float through = 1;
        // Water the ray passed through.
        int waterId = -1;
        double waterT = 0;
        int waterBy = 0;
        int waterBz = 0;

        while (by >= limitY) {
            if (skippingRock) {
                int solid = reader.solid(x, by, bz);
                if (solid == VoxelReader.UNKNOWN) {
                    return false;
                }
                if (solid > 0 || lifted && by + 1 < startY && reader.pocket(x, by, bz)) {
                    // Still inside the rock above the first open space below the cut.
                    if (tMaxY < tMaxZ) {
                        by--;
                        t = tMaxY;
                        tMaxY += tDeltaY;
                        enteredTop = true;
                    } else {
                        bz--;
                        t = tMaxZ;
                        tMaxZ += tDeltaZ;
                        enteredTop = false;
                    }
                    continue;
                }
                skippingRock = false;
            }
            int id = reader.state(x, by, bz);
            if (id == VoxelReader.UNKNOWN) {
                if (lit || by < passY) {
                    return false;
                }
                id = VoxelReader.AIR_ID;
            }
            byte kind = looks.kind[id];
            // Only solid blocks (and the see-through ones among them) have shapes.
            int shape = kind == BlockLooks.SOLID || kind == BlockLooks.GLASS ? looks.shape[id] : 0;
            boolean partial = shape != 0;
            if (partial) {
                double tIn = t;
                boolean hit = enterShape(looks.shapes[shape], fx, y0, z0, by, bz, t, Math.min(tMaxY, tMaxZ), enteredTop);
                int wet = looks.wetShape[id];
                // Waterlogged: the ray is in water from where it came into the cell, unless it met
                // the block right there, as on a fence top level with the water.
                if (wet >= 0 && waterId < 0 && (!hit || shapeT > tIn)) {
                    waterId = wet;
                    waterT = tIn;
                    waterBy = by;
                    waterBz = bz;
                }
                if (hit) {
                    t = shapeT;
                    enteredTop = shapeTop;
                } else {
                    // The ray passes beside the block's boxes, on into the next cell.
                    kind = BlockLooks.AIR;
                }
            }

            switch (kind) {
                case BlockLooks.SOLID, BlockLooks.LAVA -> {
                    if (lifted && kind == BlockLooks.SOLID && reader.ceilingUnderside(x, by, bz, lid(x, bz), limitY)) {
                        // A ray that left the rock near a step in a stepped ceiling would clip the
                        // step below it and draw a piece of ceiling floating over the floor.
                        break;
                    }
                    double hitY = y0 - t * sin;
                    double hitZ = z0 - t * cos;
                    outDepth = (float) (-(sin * hitY + cos * hitZ) - depthOrigin);
                    shade(id, x, by, bz, fx, hitY, hitZ, enteredTop, partial, sample);
                    if (through < 1) {
                        // The overlay tints the albedo; approximate by mixing it in after lighting.
                        float shine = MapLighting.luminance(outR, outG, outB) / Math.max(0.05F, MapLighting.luminance(albedoR, albedoG, albedoB));
                        outR = outR * through + overR * shine;
                        outG = outG * through + overG * shine;
                        outB = outB * through + overB * shine;
                    }
                    if (waterId >= 0) {
                        mixWater(waterId, x, waterBy, waterBz, u, y0, z0, waterT, t, sample);
                    }
                    return true;
                }
                case BlockLooks.WATER -> {
                    if (waterId < 0) {
                        waterId = id;
                        waterT = t;
                        waterBy = by;
                        waterBz = bz;
                    }
                }
                case BlockLooks.PLANT, BlockLooks.GLASS -> {
                    float w = kind == BlockLooks.PLANT ? 0.4F : 0.25F;
                    albedo(id, x, bz, true);
                    overR += through * w * albedoR;
                    overG += through * w * albedoG;
                    overB += through * w * albedoB;
                    through *= 1 - w;
                }
                default -> {
                }
            }

            if (tMaxY < tMaxZ) {
                by--;
                t = tMaxY;
                tMaxY += tDeltaY;
                enteredTop = true;
            } else {
                bz--;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                enteredTop = false;
            }
        }

        outDepth = (float) (-(sin * (y0 - t * sin) + cos * (z0 - t * cos)) - depthOrigin);
        if (skippingRock) {
            // Solid rock all the way down the slice: draw the cut face.
            outR = 0.0065F;
            outG = 0.0068F;
            outB = 0.0085F;
        } else if (waterId >= 0) {
            outR = 0;
            outG = 0;
            outB = 0;
            mixWater(waterId, x, waterBy, waterBz, u, y0, z0, waterT, waterT + 64, sample);
        } else {
            outR = 0.01F;
            outG = 0.01F;
            outB = 0.015F;
        }
        return true;
    }

    /** The surface colour in linear light. */
    private void albedo(int id, int x, int z, boolean top) {
        int base = top ? looks.top[id] : looks.side[id];
        int tinted = top ? looks.topTinted[id] : looks.sideTinted[id];
        float w = top ? looks.topTintWeight[id] : looks.sideTintWeight[id];
        int tint = tint(id, x, z);
        float bw = 1 - w;
        albedoR = SRGB_TO_LINEAR[base >> 16 & 0xFF] * bw + SRGB_TO_LINEAR[tinted >> 16 & 0xFF] * SRGB_TO_LINEAR[tint >> 16 & 0xFF] * w;
        albedoG = SRGB_TO_LINEAR[base >> 8 & 0xFF] * bw + SRGB_TO_LINEAR[tinted >> 8 & 0xFF] * SRGB_TO_LINEAR[tint >> 8 & 0xFF] * w;
        albedoB = SRGB_TO_LINEAR[base & 0xFF] * bw + SRGB_TO_LINEAR[tinted & 0xFF] * SRGB_TO_LINEAR[tint & 0xFF] * w;
    }

    private int tint(int id, int x, int z) {
        byte kind = looks.tint[id];
        if (kind == BlockLooks.TINT_NONE) {
            int constant = looks.constantTint[id];
            return constant < 0 ? 0xFFFFFF : constant;
        }
        ChunkSnapshot snapshot = reader.snapshot(x, z);
        if (snapshot == null) {
            return 0xFFFFFF;
        }
        int i = (z & 15) << 4 | x & 15;
        ChunkSnapshot.Tints tints = BiomeTints.of(snapshot);
        return switch (kind) {
            case BlockLooks.TINT_GRASS -> tints.grass()[i];
            case BlockLooks.TINT_FOLIAGE -> tints.foliage()[i];
            case BlockLooks.TINT_DRY_FOLIAGE -> tints.dryFoliage()[i];
            case BlockLooks.TINT_WATER -> tints.water()[i];
            default -> 0xFFFFFF;
        };
    }

    private void shade(int id, int x, int by, int bz, double fx, double hitY, double hitZ, boolean top, boolean partial, int sample) {
        albedo(id, x, bz, top);
        int frontY = top ? by + 1 : by;
        int frontZ = top ? bz : bz + 1;
        float ao = top ? ambientOcclusionTop(x, by + 1, bz, fx, hitZ - bz) : ambientOcclusionSouth(x, by, bz + 1, fx, hitY - by);
        float emission = looks.emission[id] / 15.0F;
        byte kind = looks.kind[id];
        if (kind == BlockLooks.LAVA) {
            float crust = 1 + (lifted ? 0.1F : 0.3F) * blockNoise(x, by, bz);
            albedoR *= crust;
            albedoG *= crust * crust;
            albedoB *= crust;
        }

        if (lifted) {
            float aoSun = Math.max(0, 1 - MapLighting.CORNER_OCCLUSION * (1 - ao));
            float aoSky = Math.min(ao, aoSun);
            double[] j = SUN_JITTER[sample];
            double sx = sunX + j[0];
            double sy = sunY + j[1];
            double sz = sunZ + j[2];
            double lambert = top ? 1 : Math.max(0, sz) / sy;
            float direct = 0;
            if (lambert > 0) {
                double ox = x + fx;
                double oy = top ? by + 1 + 1e-4 : hitY;
                double oz = top ? hitZ : bz + 1 + 1e-4;
                direct = inShadow(ox, oy, oz, sx, sy, sz) ? 0 : LID_KEY * (float) lambert * aoSun;
            }
            float sky = top ? skyTop(x, by + 1, bz, fx, hitZ - bz) : skySouth(x, by, bz + 1, fx, hitY - by);
            float ambient = LID_AMBIENT * aoSky * (1 - MapLighting.SKY_OCCLUSION * (1 - (0.15F + 0.85F * sky)));
            float warm = (float) Math.pow(reader.blockLight(x, frontY, frontZ) / 15.0F, 1.5) * LID_GLOW;
            float lr = ambient * LID_FILL_TINT[0] + direct * LID_KEY_TINT[0] + warm * 0.9F * ao;
            float lg = ambient * LID_FILL_TINT[1] + direct * LID_KEY_TINT[1] + warm * 0.75F * ao;
            float lb = ambient * LID_FILL_TINT[2] + direct * LID_KEY_TINT[2] + warm * 0.5F * ao;
            if (emission > 0) {
                float e = kind != BlockLooks.LAVA ? 0.3F + emission * 0.45F : 0.35F + emission * 0.85F;
                lr = Math.max(lr, e);
                lg = Math.max(lg, e);
                lb = Math.max(lb, e);
            }
            if (!top) {
                lr *= SIDE;
                lg *= SIDE;
                lb *= SIDE;
            }
            outR = albedoR * lr;
            outG = albedoG * lg;
            outB = albedoB * lb;
            double below = roof - HAZE_START - hitY;
            float f = below <= 0 ? 0 : Math.min(HAZE_MAX, (float) (1 - Math.exp(-below / HAZE_DEPTH)));
            if (f > 0) {
                hazeColor(x + fx, hitY, hitZ);
                outR += (hazeR - outR) * f;
                outG += (hazeG - outG) * f;
                outB += (hazeB - outB) * f;
            }
            return;
        }

        if (lit) {
            float fade = 1 - Math.min(0.5F, (startY - (float) hitY) / 60);
            MapLighting.cave(reader.blockLight(x, frontY, frontZ), reader.skyLight(x, frontY, frontZ), ao, fade, emission, top ? 1 : SIDE, light);
        } else {
            double[] j = SUN_JITTER[sample];
            double sx = sunX + j[0];
            double sy = sunY + j[1];
            double sz = sunZ + j[2];
            double lambert = top ? 1 : Math.max(0, sz) / sy;
            double oy = top ? (partial ? hitY : by + 1) + 1e-4 : hitY;
            double oz = top ? hitZ : (partial ? hitZ : bz + 1) + 1e-4;
            boolean shadowed = lambert > 0 && inShadow(x + fx, oy, oz, sx, sy, sz);
            float sky = top ? skyTop(x, by + 1, bz, fx, hitZ - bz) : skySouth(x, by, bz + 1, fx, hitY - by);
            MapLighting.surface(lambert, shadowed, ao, sky, emission, top ? 1 : SIDE, hitY, light);
        }
        outR = albedoR * light[0];
        outG = albedoG * light[1];
        outB = albedoB * light[2];
    }

    // The haze colour worked out by the last hazeColor call.
    private float hazeR;
    private float hazeG;
    private float hazeB;

    /** The biome's fog blended over the four nearest biome cells, dimmed so it can't outshine the ground. */
    private void hazeColor(double x, double y, double z) {
        double qx = x / 4 - 0.5;
        double qz = z / 4 - 0.5;
        int x0 = (int) Math.floor(qx);
        int z0 = (int) Math.floor(qz);
        int qy = (int) Math.floor(y) >> 2;
        double fx = qx - x0;
        double fz = qz - z0;
        int[] cells = {fogCell(x0, qy, z0), fogCell(x0 + 1, qy, z0), fogCell(x0, qy, z0 + 1), fogCell(x0 + 1, qy, z0 + 1)};
        float r = bilinear(SRGB_TO_LINEAR[cells[0] >> 16 & 0xFF], SRGB_TO_LINEAR[cells[1] >> 16 & 0xFF], SRGB_TO_LINEAR[cells[2] >> 16 & 0xFF], SRGB_TO_LINEAR[cells[3] >> 16 & 0xFF], fx, fz);
        float g = bilinear(SRGB_TO_LINEAR[cells[0] >> 8 & 0xFF], SRGB_TO_LINEAR[cells[1] >> 8 & 0xFF], SRGB_TO_LINEAR[cells[2] >> 8 & 0xFF], SRGB_TO_LINEAR[cells[3] >> 8 & 0xFF], fx, fz);
        float b = bilinear(SRGB_TO_LINEAR[cells[0] & 0xFF], SRGB_TO_LINEAR[cells[1] & 0xFF], SRGB_TO_LINEAR[cells[2] & 0xFF], SRGB_TO_LINEAR[cells[3] & 0xFF], fx, fz);
        float scale = HAZE_LIGHT * Math.min(1, HAZE_MAX_LUMINANCE / Math.max(1e-6F, MapLighting.luminance(r, g, b)));
        hazeR = r * scale;
        hazeG = g * scale;
        hazeB = b * scale;
    }

    private int fogCell(int qx, int qy, int qz) {
        long at = (long) (qx & 0x3FFFFFF) << 38 | (long) (qz & 0x3FFFFFF) << 12 | qy + 2048 & 0xFFF;
        int color = fogAt.get(at);
        if (color < 0) {
            ChunkSnapshot snapshot = reader.snapshot(qx << 2, qz << 2);
            Holder<Biome> biome = snapshot == null ? null : snapshot.knownBiome(qx, qy, qz);
            color = biome == null ? NETHER_WASTES_FOG : fogColor(biome);
            fogAt.put(at, color);
        }
        return color;
    }

    /** The fog colours of the game's own Nether biomes; a mod's biome reads as Nether Wastes. */
    private static int fogColor(Holder<Biome> biome) {
        if (biome.is(Biomes.CRIMSON_FOREST)) {
            return 0x330303;
        }
        if (biome.is(Biomes.WARPED_FOREST)) {
            return 0x1A051A;
        }
        if (biome.is(Biomes.SOUL_SAND_VALLEY)) {
            return 0x1B4745;
        }
        if (biome.is(Biomes.BASALT_DELTAS)) {
            return 0x685F70;
        }
        return NETHER_WASTES_FOG;
    }

    /** Lays the water the ray passed through, from {@code tEntry} to {@code tHit}, over the colour so far. */
    private void mixWater(int waterId, int x, int by, int bz, double u, double y0, double z0, double tEntry, double tHit, int sample) {
        float belowR = outR;
        float belowG = outG;
        float belowB = outB;
        albedo(waterId, x, bz, true);
        float wr = albedoR;
        float wg = albedoG;
        float wb = albedoB;
        float lightLevel;
        if (lit) {
            lightLevel = MapLighting.caveWater(reader.blockLight(x, by + 1, bz), reader.skyLight(x, by + 1, bz));
        } else {
            double sy = sunY + SUN_JITTER[sample][1];
            double oy = y0 - tEntry * sin + 1e-4;
            double oz = z0 - tEntry * cos;
            boolean shadow = inShadow(u, oy, oz, sunX, sy, sunZ);
            float sky = skyTop(x, by + 1, bz, u - x, oz - bz);
            float ambient = MapLighting.AMBIENT * (1 - MapLighting.SKY_OCCLUSION * (1 - (0.15F + 0.85F * sky)));
            lightLevel = ambient + (shadow ? 0 : MapLighting.SUN_STRENGTH);
            lightLevel *= 1 + MapLighting.HEIGHT_SHADE * Math.max(-1, Math.min(1, (by + 1 - 64) / 64.0F));
        }
        float depth = (float) Math.max(0, tHit - tEntry);
        outWater = depth;
        float peak = Math.max(1e-4F, Math.max(wr, Math.max(wg, wb)));
        float tr = (float) Math.exp(-depth * (0.45 * (1 - wr / peak) + 0.16));
        float tg = (float) Math.exp(-depth * (0.45 * (1 - wg / peak) + 0.16));
        float tb = (float) Math.exp(-depth * (0.45 * (1 - wb / peak) + 0.16));
        float deep = lightLevel * (0.3F + 0.7F * (float) Math.exp(-depth / 18));
        float l = MapLighting.luminance(wr, wg, wb);
        float deepR = Math.max(0, l + (wr - l) * 1.2F) * deep;
        float deepG = Math.max(0, l + (wg - l) * 1.2F) * deep;
        float deepB = Math.max(0, l + (wb - l) * 1.2F) * deep;
        outR = belowR * tr + deepR * (1 - tr);
        outG = belowG * tg + deepG * (1 - tg);
        outB = belowB * tb + deepB * (1 - tb);
    }

    private boolean inShadow(double ox, double oy, double oz, double dx, double dy, double dz) {
        return blocked(ox, oy, oz, dx, dy, dz, SHADOW_DISTANCE, SHADOW_STEPS, true);
    }

    /** Sky visibility on a top face at {@code y}, blended from its four corners. */
    private float skyTop(int x, int y, int z, double fx, double fz) {
        return bilinear(skyAt(x, y, z), skyAt(x + 1, y, z), skyAt(x, y, z + 1), skyAt(x + 1, y, z + 1), fx, fz);
    }

    /** Sky visibility on a south face at {@code z}, blended from its four corners; {@code fy} runs bottom to top. */
    private float skySouth(int x, int y, int z, double fx, double fy) {
        return bilinear(skyAt(x, y + 1, z), skyAt(x + 1, y + 1, z), skyAt(x, y, z), skyAt(x + 1, y, z), fx, 1 - fy);
    }

    /** Share of the sky seen from a block corner, 0..1, from a few short rays upward. */
    float skyAt(int x, int y, int z) {
        long key = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
        float cached = skyAtCorner.getOrDefault(key, -1F);
        if (cached >= 0) {
            return cached;
        }
        double open = 0;
        double total = 0;
        for (int i = 0; i < SKY_RAYS.length; i++) {
            double[] d = SKY_RAYS[i];
            // Start a hair along the ray, so it begins in the block it heads into, not on a boundary.
            // Sky light is soft and sampled at cube corners anyway: a smaller block blocks it like a cube.
            if (!blocked(x + d[0] * 1e-3, y + d[1] * 1e-3, z + d[2] * 1e-3, d[0], d[1], d[2], SKY_REACH, SKY_REACH * 3, false)) {
                open += SKY_WEIGHTS[i];
            }
            total += SKY_WEIGHTS[i];
        }
        float sky = (float) (open / total);
        skyAtCorner.put(key, sky);
        return sky;
    }

    /**
     * Walks from a point along a direction for up to {@code distance} blocks; true if a solid block
     * is in the way. Without {@code shapes}, a block smaller than a cube blocks the way like a cube.
     */
    private boolean blocked(double ox, double oy, double oz, double dx, double dy, double dz, double distance, int steps, boolean shapes) {
        double ix = 1 / dx;
        double iy = 1 / dy;
        double iz = 1 / dz;
        int cx = (int) Math.floor(ox);
        int cy = (int) Math.floor(oy);
        int cz = (int) Math.floor(oz);
        // Under a lifted lid its rock doesn't block light.
        int start = cy < rayTop && !(lifted && cy >= lid(cx, cz)) ? reader.solid(cx, cy, cz) : 0;
        if (start == 1 || start == 2 && (!shapes || meetsShape(cx, cy, cz, ox, oy, oz, dx, dy, dz, ix, iy, iz))) {
            return true;
        }
        // Under a lifted lid nothing above the cut is left to block light.
        int top = lifted ? Math.min(rayTop, startY) : rayTop;
        if (dy > 0) {
            // A ray that only climbs can't be blocked once it is above every solid block in the
            // chunks it can reach, so it stops there instead of walking on through open sky.
            double ex = ox + dx * distance;
            double ez = oz + dz * distance;
            top = Math.min(top, reader.solidTop(
                ((int) Math.floor(Math.min(ox, ex)) - 1) >> 4, ((int) Math.floor(Math.min(oz, ez)) - 1) >> 4,
                ((int) Math.floor(Math.max(ox, ex)) + 1) >> 4, ((int) Math.floor(Math.max(oz, ez)) + 1) >> 4
            ));
        }
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(ix);
        double tDeltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(iy);
        double tDeltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(iz);
        double tMaxX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? cx + 1 - ox : ox - cx) * tDeltaX;
        double tMaxY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? cy + 1 - oy : oy - cy) * tDeltaY;
        double tMaxZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? cz + 1 - oz : oz - cz) * tDeltaZ;
        for (int i = 0; i < steps; i++) {
            if (Math.min(tMaxX, Math.min(tMaxY, tMaxZ)) > distance) {
                return false;
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                cx += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                cy += stepY;
                tMaxY += tDeltaY;
            } else {
                cz += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (cy >= top) {
                return false;
            }
            int solid = reader.solid(cx, cy, cz);
            if (solid == VoxelReader.UNKNOWN) {
                return false;
            }
            if (lifted && solid > 0 && cy >= lid(cx, cz)) {
                continue;
            }
            if (solid == 1 || solid == 2 && (!shapes || meetsShape(cx, cy, cz, ox, oy, oz, dx, dy, dz, ix, iy, iz))) {
                return true;
            }
        }
        return false;
    }

    /** Whether a ray from (ox, oy, oz) along (dx, dy, dz) meets the solid block in this cell's own shape. */
    private boolean meetsShape(int cx, int cy, int cz, double ox, double oy, double oz, double dx, double dy, double dz, double ix, double iy, double iz) {
        int id = reader.state(cx, cy, cz);
        int shape = id < 0 ? 0 : looks.shape[id];
        if (shape == 0) {
            return true;
        }
        float[] boxes = looks.shapes[shape];
        double px = ox - cx;
        double py = oy - cy;
        double pz = oz - cz;
        for (int i = 0; i < boxes.length; i += 6) {
            double near = 0;
            double far = Double.MAX_VALUE;
            for (int axis = 0; axis < 3 && near <= far; axis++) {
                double p = axis == 0 ? px : axis == 1 ? py : pz;
                double d = axis == 0 ? dx : axis == 1 ? dy : dz;
                double inv = axis == 0 ? ix : axis == 1 ? iy : iz;
                double min = boxes[i + axis];
                double max = boxes[i + 3 + axis];
                if (d == 0) {
                    if (p < min || p > max) {
                        far = -1;
                    }
                } else {
                    double t0 = (min - p) * inv;
                    double t1 = (max - p) * inv;
                    near = Math.max(near, Math.min(t0, t1));
                    far = Math.min(far, Math.max(t0, t1));
                }
            }
            if (near <= far) {
                return true;
            }
        }
        return false;
    }

    // Where the last enterShape call met the boxes: the ray distance, and whether through a top face.
    private double shapeT;
    private boolean shapeTop;

    /**
     * Whether a camera ray crossing cell ({@code by}, {@code bz}) between {@code tIn} and
     * {@code tOut} meets one of a block's boxes, and where first. The ray runs {@code fx} across the
     * cell, heading down and north; {@code inTop} says whether it came into the cell from above.
     */
    private boolean enterShape(float[] boxes, double fx, double y0, double z0, int by, int bz, double tIn, double tOut, boolean inTop) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < boxes.length; i += 6) {
            if (fx < boxes[i] || fx >= boxes[i + 3]) {
                continue;
            }
            double tTop = (y0 - by - boxes[i + 4]) * invSin;
            double tSouth = (z0 - bz - boxes[i + 5]) * invCos;
            double enter = Math.max(tIn, Math.max(tTop, tSouth));
            double exit = Math.min(tOut, Math.min((y0 - by - boxes[i + 1]) * invSin, (z0 - bz - boxes[i + 2]) * invCos));
            if (enter < exit && enter < best) {
                best = enter;
                // A box flush with the cell's side is met where the ray came into the cell.
                shapeTop = enter == tIn ? inTop : tTop >= tSouth;
            }
        }
        shapeT = best;
        return best != Double.MAX_VALUE;
    }

    boolean occludes(int x, int y, int z) {
        return reader.solid(x, y, z) > 0;
    }

    /** Smooth corner occlusion on a top face, from the eight blocks around the cell above it. */
    private float ambientOcclusionTop(int x, int y, int z, double fx, double fz) {
        boolean n = occludes(x, y, z - 1);
        boolean s = occludes(x, y, z + 1);
        boolean w = occludes(x - 1, y, z);
        boolean e = occludes(x + 1, y, z);
        float nw = corner(w, n, occludes(x - 1, y, z - 1));
        float ne = corner(e, n, occludes(x + 1, y, z - 1));
        float sw = corner(w, s, occludes(x - 1, y, z + 1));
        float se = corner(e, s, occludes(x + 1, y, z + 1));
        return bilinear(nw, ne, sw, se, fx, fz);
    }

    /** Same for a south face; {@code z} is the open layer in front of it, {@code fy} runs bottom to top. */
    private float ambientOcclusionSouth(int x, int y, int z, double fx, double fy) {
        boolean up = occludes(x, y + 1, z);
        boolean down = occludes(x, y - 1, z);
        boolean w = occludes(x - 1, y, z);
        boolean e = occludes(x + 1, y, z);
        float tw = corner(w, up, occludes(x - 1, y + 1, z));
        float te = corner(e, up, occludes(x + 1, y + 1, z));
        float bw = corner(w, down, occludes(x - 1, y - 1, z));
        float be = corner(e, down, occludes(x + 1, y - 1, z));
        return bilinear(tw, te, bw, be, fx, 1 - fy);
    }

    static float corner(boolean side1, boolean side2, boolean diagonal) {
        if (side1 && side2) {
            return AO_LEVELS[0];
        }
        return AO_LEVELS[3 - (side1 ? 1 : 0) - (side2 ? 1 : 0) - (diagonal ? 1 : 0)];
    }

    private static float bilinear(float a, float b, float c, float d, double fx, double fy) {
        float top = (float) (a + (b - a) * fx);
        float bottom = (float) (c + (d - c) * fx);
        return (float) (top + (bottom - top) * fy);
    }

    /** -1..1, fixed per block position. */
    static float blockNoise(int x, int y, int z) {
        int h = x * 73_856_093 ^ y * 19_349_663 ^ z * 83_492_791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return ((h & 0xFFFF) / 32767.5F) - 1;
    }

    /** Graded the way the map is meant to look, then back to the screen's colours. */
    private static int argb(float r, float g, float b, int alpha) {
        float l = MapLighting.luminance(r, g, b);
        return alpha << 24 | srgb(l + (r - l) * MapLighting.SATURATION) << 16 | srgb(l + (g - l) * MapLighting.SATURATION) << 8 | srgb(l + (b - l) * MapLighting.SATURATION);
    }

    private static int srgb(float c) {
        return LINEAR_TO_SRGB[Math.min(4095, Math.max(0, (int) (c * MapLighting.EXPOSURE * 4095)))];
    }
}
