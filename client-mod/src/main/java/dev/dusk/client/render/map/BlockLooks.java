package dev.dusk.client.render.map;

import com.mojang.blaze3d.platform.NativeImage;
import dev.dusk.client.mixin.minimap.SpriteContentsAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * What every block state looks like on the map, read once from the game's own models: the colour of
 * its top and sides, what kind of thing it is to a ray, its outline and how it is tinted. Ported from
 * Diorama (MIT, see NOTICE). Built on the client thread, then only read.
 */
final class BlockLooks {
    static final byte AIR = 0;
    static final byte SOLID = 1;
    static final byte WATER = 2;
    static final byte LAVA = 3;
    static final byte GLASS = 4;
    static final byte PLANT = 5;

    static final byte TINT_NONE = 0;
    static final byte TINT_GRASS = 1;
    static final byte TINT_FOLIAGE = 2;
    static final byte TINT_DRY_FOLIAGE = 3;
    static final byte TINT_WATER = 4;

    /** A shape with more boxes than this counts as its bounding box. */
    private static final int MAX_BOXES = 16;

    private static BlockLooks current;
    private static Object builtFrom;

    final byte[] kind;
    final byte[] tint;
    final byte[] emission;
    final int[] top;
    final int[] topTinted;
    final float[] topTintWeight;
    final int[] side;
    final int[] sideTinted;
    final float[] sideTintWeight;
    final int[] constantTint;
    final int[] shape;
    /** Entry 0 is unused; each other holds six floats per box. */
    float[][] shapes;
    /** The water a waterlogged block stands in, or -1. */
    final int[] wetShape;

    /** One quad of a block's model, however the running version stores it. */
    record Quad(TextureAtlasSprite sprite, int tintIndex, Direction direction) {
    }

    /** One part of a block's model. */
    interface Part {
        List<Quad> quads(Direction direction);
    }

    /** How a block or fluid is tinted: by a biome colour, a fixed colour, or (-1) not at all. */
    record Tint(byte kind, int constant) {
        static final Tint NONE = new Tint(TINT_NONE, -1);

        /** Asks tint code for its colour in the probe's world, then out of the world if that says nothing the map can use. */
        static Tint ask(TintProbe probe, IntSupplier inWorld, IntSupplier outOfWorld) {
            probe.reset();
            int color;
            try {
                color = inWorld.getAsInt();
            } catch (RuntimeException | LinkageError e) {
                color = -1;
            }
            byte kind = probe.kind();
            if (kind != TINT_NONE) {
                return new Tint(kind, -1);
            }
            if (probe.askedForBiome() || color == -1) {
                try {
                    color = outOfWorld.getAsInt();
                } catch (RuntimeException | LinkageError e) {
                    return NONE;
                }
            }
            return (color & 0xFFFFFF) == 0xFFFFFF ? NONE : new Tint(TINT_NONE, color & 0xFFFFFF);
        }
    }

    private BlockLooks(int size) {
        kind = new byte[size];
        tint = new byte[size];
        emission = new byte[size];
        top = new int[size];
        topTinted = new int[size];
        topTintWeight = new float[size];
        side = new int[size];
        sideTinted = new int[size];
        sideTintWeight = new float[size];
        constantTint = new int[size];
        shape = new int[size];
        wetShape = new int[size];
        Arrays.fill(wetShape, -1);
    }

    /** The table, built now if there is none or resource packs changed. Client thread, after models are loaded. */
    static BlockLooks current() {
        if (current == null || builtFrom != BlockFaces.modelsKey()) {
            current = build();
            builtFrom = BlockFaces.modelsKey();
        }
        return current;
    }

    static int id(BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id < 0) {
            id = Block.BLOCK_STATE_REGISTRY.getId(state.getBlock().defaultBlockState());
        }
        if (id < 0) {
            id = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        }
        return Math.max(id, 0);
    }

    private static BlockLooks build() {
        int size = Block.BLOCK_STATE_REGISTRY.size();
        BlockLooks looks = new BlockLooks(size);
        Map<TextureAtlasSprite, float[]> sprites = new IdentityHashMap<>();
        RandomSource random = RandomSource.create(42L);
        TintWorld probe = new TintWorld();
        BiomeTints.setWater(waterResolver(probe));
        Map<VoxelShape, Integer> shapeByVoxels = new IdentityHashMap<>();
        Map<String, Integer> shapeByBoxes = new HashMap<>();
        List<float[]> shapes = new ArrayList<>();
        shapes.add(new float[0]);

        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            int id = Block.BLOCK_STATE_REGISTRY.getId(state);
            if (id < 0 || id >= size) {
                continue;
            }
            looks.emission[id] = (byte) state.getLightEmission();
            looks.constantTint[id] = -1;
            // A mod's block can fail when asked for its shape; it counts as a full block.
            byte kind = SOLID;
            try {
                kind = classify(state);
                if (kind == SOLID) {
                    looks.shape[id] = shapeIndex(state, shapeByVoxels, shapeByBoxes, shapes);
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
            looks.kind[id] = kind;
            if (kind == AIR) {
                continue;
            }

            FaceColor topFace = new FaceColor();
            FaceColor sideFace = new FaceColor();
            try {
                for (Part part : BlockFaces.parts(state, random)) {
                    accumulate(topFace, part.quads(Direction.UP), sprites);
                    accumulate(sideFace, part.quads(Direction.SOUTH), sprites);
                    if (sideFace.isEmpty()) {
                        accumulate(sideFace, part.quads(Direction.NORTH), sprites);
                    }
                    accumulateLoose(topFace, sideFace, part.quads(null), sprites);
                }
            } catch (RuntimeException | LinkageError e) {
                topFace = new FaceColor();
                sideFace = new FaceColor();
            }
            if (topFace.isEmpty() && sideFace.isEmpty()) {
                // Block entities (chests, signs, beds) have no block model; use the particle texture.
                TextureAtlasSprite particle;
                try {
                    particle = BlockFaces.particle(state);
                } catch (RuntimeException | LinkageError e) {
                    particle = null;
                }
                if (particle != null && !isMissing(particle)) {
                    float[] avg = spriteAverage(particle, sprites);
                    topFace.add(avg, -1);
                    sideFace.add(avg, -1);
                }
            }
            if (topFace.isEmpty() && sideFace.isEmpty()) {
                // No texture to go by: the block's colour on the game's own maps, seen through where they see through it.
                int mapColor = mapColor(state);
                if (mapColor == 0) {
                    looks.kind[id] = AIR;
                    looks.shape[id] = 0;
                    continue;
                }
                float[] flat = {(mapColor >> 16 & 0xFF) / 255.0F, (mapColor >> 8 & 0xFF) / 255.0F, (mapColor & 0xFF) / 255.0F, 1.0F};
                topFace.add(flat, -1);
                sideFace.add(flat, -1);
            }
            if (topFace.isEmpty()) {
                topFace = sideFace;
            }
            if (sideFace.isEmpty()) {
                sideFace = topFace;
            }
            looks.top[id] = topFace.untinted();
            looks.topTinted[id] = topFace.tinted();
            looks.topTintWeight[id] = topFace.tintWeight();
            looks.side[id] = sideFace.untinted();
            looks.sideTinted[id] = sideFace.tinted();
            looks.sideTintWeight[id] = sideFace.tintWeight();

            if (kind == SOLID && !state.is(BlockTags.LEAVES) && topFace.coverage() < 0.55F) {
                looks.kind[id] = GLASS;
            }

            int tintIndex = topFace.tintIndex() >= 0 ? topFace.tintIndex() : sideFace.tintIndex();
            if (tintIndex >= 0) {
                Tint tint = BlockFaces.blockTint(state, tintIndex, probe);
                looks.tint[id] = tint.kind();
                looks.constantTint[id] = tint.constant();
            }
        }

        // Every cell holding a fluid and nothing solid draws as the fluid's still texture.
        Map<Fluid, Integer> fluidColors = new IdentityHashMap<>();
        Map<Fluid, Tint> fluidTints = new IdentityHashMap<>();
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            int id = Block.BLOCK_STATE_REGISTRY.getId(state);
            if (id < 0 || id >= size || looks.kind[id] != WATER) {
                continue;
            }
            FluidState fluid = state.getFluidState();
            int color = fluidColors.computeIfAbsent(fluid.getType(), type -> fluidColor(fluid, sprites));
            Tint tint = fluidTints.computeIfAbsent(fluid.getType(), type -> fluidTint(fluid, probe));
            if (color < 0) {
                continue;
            }
            looks.top[id] = color;
            looks.topTinted[id] = color;
            looks.topTintWeight[id] = 1;
            looks.side[id] = color;
            looks.sideTinted[id] = color;
            looks.sideTintWeight[id] = 1;
            looks.tint[id] = tint.kind();
            looks.constantTint[id] = tint.constant();
        }

        looks.shapes = shapes.toArray(new float[0][]);
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            int id = Block.BLOCK_STATE_REGISTRY.getId(state);
            if (id >= 0 && id < size && looks.shape[id] != 0 && !state.getFluidState().isEmpty()) {
                int fluid = Block.BLOCK_STATE_REGISTRY.getId(state.getFluidState().createLegacyBlock());
                if (fluid >= 0 && fluid < size && looks.kind[fluid] == WATER) {
                    looks.wetShape[id] = fluid;
                }
            }
        }
        return looks;
    }

    private static int shapeIndex(BlockState state, Map<VoxelShape, Integer> byVoxels, Map<String, Integer> byBoxes, List<float[]> shapes) {
        VoxelShape outline;
        try {
            outline = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
        Integer known = byVoxels.get(outline);
        if (known != null) {
            return known;
        }
        int index = 0;
        if (!outline.isEmpty() && outline != Shapes.block()) {
            List<AABB> boxes = outline.toAabbs();
            if (boxes.size() > MAX_BOXES) {
                boxes = List.of(outline.bounds());
            }
            float[] flat = new float[boxes.size() * 6];
            int n = 0;
            boolean full = false;
            for (AABB box : boxes) {
                // Some mods' outlines reach into the next block; only the part in this one counts.
                float[] b = {clamp01(box.minX), clamp01(box.minY), clamp01(box.minZ), clamp01(box.maxX), clamp01(box.maxY), clamp01(box.maxZ)};
                if (b[0] >= b[3] || b[1] >= b[4] || b[2] >= b[5]) {
                    continue;
                }
                System.arraycopy(b, 0, flat, n, 6);
                n += 6;
                full |= b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 1 && b[4] == 1 && b[5] == 1;
            }
            float[] kept = Arrays.copyOf(flat, n);
            if (!full && n > 0) {
                index = byBoxes.computeIfAbsent(Arrays.toString(kept), key -> {
                    shapes.add(kept);
                    return shapes.size() - 1;
                });
            }
        }
        byVoxels.put(outline, index);
        return index;
    }

    private static float clamp01(double v) {
        return (float) Math.max(0, Math.min(1, v));
    }

    private static byte classify(BlockState state) {
        FluidState fluid = state.getFluidState();
        if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE && fluid.isEmpty()) {
            return AIR;
        }
        boolean noCollision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        if (!fluid.isEmpty() && noCollision) {
            return fluid.is(FluidTags.LAVA) ? LAVA : WATER;
        }
        if (noCollision) {
            return state.getBlock() instanceof SnowLayerBlock ? SOLID : PLANT;
        }
        return SOLID;
    }

    /** A fluid's colour as 0xRRGGBB, or -1 if its texture can't be found. */
    private static int fluidColor(FluidState fluid, Map<TextureAtlasSprite, float[]> sprites) {
        TextureAtlasSprite sprite;
        try {
            sprite = BlockFaces.fluidSprite(fluid);
        } catch (RuntimeException | LinkageError e) {
            sprite = null;
        }
        if (sprite == null || isMissing(sprite)) {
            return -1;
        }
        float[] avg = spriteAverage(sprite, sprites);
        return avg[3] <= 0 ? -1 : FaceColor.pack(avg[0], avg[1], avg[2]);
    }

    private static Tint fluidTint(FluidState fluid, TintWorld probe) {
        try {
            return BlockFaces.fluidTint(fluid, probe);
        } catch (RuntimeException | LinkageError e) {
            return Tint.NONE;
        }
    }

    /** The biome colour water's tint code asks for, the game's own if it asks for none. */
    private static ColorResolver waterResolver(TintWorld probe) {
        probe.reset();
        fluidTint(Fluids.WATER.defaultFluidState(), probe);
        ColorResolver asked = probe.asked();
        return asked != null ? asked : BiomeColors.WATER_COLOR_RESOLVER;
    }

    private static void accumulate(FaceColor face, List<Quad> quads, Map<TextureAtlasSprite, float[]> sprites) {
        for (Quad quad : quads) {
            if (!isMissing(quad.sprite())) {
                face.add(spriteAverage(quad.sprite(), sprites), quad.tintIndex());
            }
        }
    }

    /** Cross-shaped plants and other geometry no block face hides. */
    private static void accumulateLoose(FaceColor top, FaceColor side, List<Quad> quads, Map<TextureAtlasSprite, float[]> sprites) {
        for (Quad quad : quads) {
            if (isMissing(quad.sprite())) {
                continue;
            }
            float[] avg = spriteAverage(quad.sprite(), sprites);
            top.add(avg, quad.tintIndex());
            if (quad.direction() != Direction.UP && quad.direction() != Direction.DOWN) {
                side.add(avg, quad.tintIndex());
            }
        }
    }

    private static boolean isMissing(TextureAtlasSprite sprite) {
        return sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation());
    }

    private static int mapColor(BlockState state) {
        try {
            return state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    private static float[] spriteAverage(TextureAtlasSprite sprite, Map<TextureAtlasSprite, float[]> sprites) {
        return sprites.computeIfAbsent(sprite, BlockLooks::average);
    }

    /** {r, g, b, coverage} of the sprite's visible pixels; stored values are averaged as they are, not in linear light. */
    private static float[] average(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        NativeImage image = ((SpriteContentsAccessor) contents).duskclient$originalImage();
        if (image == null) {
            return new float[] {1.0F, 0.0F, 1.0F, 1.0F};
        }
        int w = Math.min(contents.width(), image.getWidth());
        int h = Math.min(contents.height(), image.getHeight());
        double r = 0;
        double g = 0;
        double b = 0;
        double weight = 0;
        int visible = 0;
        try {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = MapCompat.pixel(image, x, y);
                    int a = argb >>> 24;
                    if (a < 26) {
                        continue;
                    }
                    double wa = a / 255.0;
                    r += (argb >> 16 & 0xFF) * wa;
                    g += (argb >> 8 & 0xFF) * wa;
                    b += (argb & 0xFF) * wa;
                    weight += wa;
                    visible++;
                }
            }
        } catch (IllegalStateException freed) {
            return new float[4];
        }
        if (weight == 0) {
            return new float[4];
        }
        return new float[] {(float) (r / weight / 255), (float) (g / weight / 255), (float) (b / weight / 255), (float) visible / (w * h)};
    }

    /** Running average of the quads that make up one face direction. */
    private static final class FaceColor {
        private float ur;
        private float ug;
        private float ub;
        private float uw;
        private float tr;
        private float tg;
        private float tb;
        private float tw;
        private float coverage;
        private int count;
        private int tintIndex = -1;

        void add(float[] avg, int tintIndex) {
            float w = avg[3];
            if (w <= 0) {
                return;
            }
            if (tintIndex >= 0) {
                if (this.tintIndex < 0) {
                    this.tintIndex = tintIndex;
                }
                tr += avg[0] * w;
                tg += avg[1] * w;
                tb += avg[2] * w;
                tw += w;
            } else {
                ur += avg[0] * w;
                ug += avg[1] * w;
                ub += avg[2] * w;
                uw += w;
            }
            coverage = Math.max(coverage, w);
            count++;
        }

        boolean isEmpty() {
            return count == 0;
        }

        float coverage() {
            return coverage;
        }

        int tintIndex() {
            return tintIndex;
        }

        int untinted() {
            return uw == 0 ? 0 : pack(ur / uw, ug / uw, ub / uw);
        }

        int tinted() {
            return tw == 0 ? 0 : pack(tr / tw, tg / tw, tb / tw);
        }

        float tintWeight() {
            float total = uw + tw;
            return total == 0 ? 0 : tw / total;
        }

        static int pack(float r, float g, float b) {
            return clamp255(r) << 16 | clamp255(g) << 8 | clamp255(b);
        }

        private static int clamp255(float v) {
            return Math.max(0, Math.min(255, Math.round(v * 255)));
        }
    }
}
