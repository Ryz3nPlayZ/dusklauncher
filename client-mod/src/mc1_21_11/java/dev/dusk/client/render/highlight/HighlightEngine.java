package dev.dusk.client.render.highlight;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.mixin.VoxelShapeInvoker;
import dev.dusk.client.modules.render.BlockHighlight;
import dev.dusk.client.modules.render.BlockHighlight.Depth;
import dev.dusk.client.modules.render.BlockHighlight.Faces;
import dev.dusk.client.modules.render.BlockHighlight.Layer;
import dev.dusk.client.modules.render.BlockHighlight.Paint;
import dev.dusk.client.modules.render.BlockHighlight.Shape;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Block Highlight's state and drawing, worked out from Custom Block Highlight
 * by tektonikal (GPL-3.0) as behaviour only: the box glides between targets,
 * outlines and fill fade per face, shape edges morph line by line, and the
 * whole thing scales, thickens and turns in and out. HighlightGpu does the
 * drawing for each game version; HighlightPlatform supplies the camera, the
 * model quads and the render hooks.
 */
public final class HighlightEngine {
    private static final Direction[] DIRS = Direction.values();

    private static AABB box = new AABB(0, 0, 0, 0, 0, 0);
    private static final Quaternionf rotation = new Quaternionf();
    private static Direction lastHorizontal = Direction.NORTH;
    private static float scaleProg, lineProg;
    private static final float[] fillFades = new float[6];
    /** Per outline layer, CBH's numbering (2 is the primary). */
    private static final float[][] edgeFades = new float[3][6];
    private static final float[] edgeAlpha = new float[3];

    private static List<Seg> shapeSegs = new ArrayList<>(), modelSegs = new ArrayList<>();
    private static final List<Seg> leaving = new ArrayList<>();

    private static boolean featureFrame, featureObstructed;

    // the last target, kept so the same shape object comes back while nothing changes
    private static VoxelShape cached;
    private static BlockPos keyPos;
    private static BlockState keyState;
    private static VoxelShape keyRaw, keyNeighbour;
    private static Direction keyDir;
    private static AABB keyEntityBox;

    private static VoxelShape boundsOf;
    private static AABB bounds;
    private static VoxelShape edgesOf;
    private static List<Seg> edgeTargets = List.of();
    private static Vec3 edgeCenter = Vec3.ZERO;
    private static VoxelShape modelOf;
    private static Direction modelDir;
    private static BlockState modelNeighbour;
    private static List<Seg> modelTargets = List.of();
    private static Vec3 modelCenter = Vec3.ZERO;

    private HighlightEngine() {}

    // ---- entry points ----

    /** After the level is drawn: updates and draws, unless the feature pass already did this frame. */
    public static void mainLoop(PoseStack pose) {
        if (featureFrame) {
            featureFrame = false;
            return;
        }
        BlockHighlight m = BlockHighlight.active();
        if (m == null) return;
        HitResult hit = tick(m);
        if (hit == null || !anythingVisible(m)) return;
        Profiler.get().push("duskclient_block_highlight");
        try {
            draw(m, pose, obstructed(m, hit));
            HighlightGpu.flush();
        } finally {
            Profiler.get().pop();
        }
    }

    /** While the frame's submits are collected (26.2+): updates, and says whether to submit a draw. */
    public static boolean prepareFeature() {
        featureFrame = true;
        BlockHighlight m = BlockHighlight.active();
        if (m == null) return false;
        HitResult hit = tick(m);
        if (hit == null || !anythingVisible(m)) return false;
        featureObstructed = obstructed(m, hit);
        return true;
    }

    /** The feature pass's draw, into whatever HighlightGpu.begin hands out. */
    public static void drawFeature() {
        BlockHighlight m = BlockHighlight.active();
        if (m != null) draw(m, new PoseStack(), featureObstructed);
    }

    // ---- per frame ----

    private static HitResult tick(BlockHighlight m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || !m.customOn()) return null;
        if (Compat.hudHidden(mc) && !m.showWhenNoHud.get()) return null;
        if (mc.gameMode.getPlayerMode().isBlockPlacingRestricted() && !m.showWhenNoInteraction.get()) return null;
        Profiler.get().push("duskclient_block_highlight_tick");
        try {
            HitResult hit = target(m);
            if (hit != null) {
                easeBoxAndEdges(m, hit, shapeOf(m, hit));
                updateFades(m, hit);
            }
            return hit;
        } finally {
            Profiler.get().pop();
        }
    }

    private static boolean anythingVisible(BlockHighlight m) {
        if (m.fillEnabled.get()) {
            for (float f : fillFades) if (Math.round(f) >= 1) return true;
        }
        if (!m.primary.on()) return false;
        for (int i = 0; i < 3; i++) {
            Layer l = m.layer(i);
            if (!l.on()) continue;
            if (l.shape() == Shape.CLASSIC_BOX) {
                for (float f : edgeFades[i]) if (Math.round(f) >= 1) return true;
            } else if (Math.round(edgeAlpha[i]) >= 1) {
                return true;
            }
        }
        return false;
    }

    private static HitResult target(BlockHighlight m) {
        Minecraft mc = Minecraft.getInstance();
        Entity cam = mc.getCameraEntity();
        if (mc.level == null || mc.player == null || cam == null) return null;
        if (mc.hitResult instanceof EntityHitResult) return mc.hitResult;
        if (m.allowLiquids.get() && holdingFluidItem(m)) {
            HitResult fluid = pick(m, cam, mc.player.blockInteractionRange(), mc.getDeltaTracker().getRealtimeDeltaTicks());
            if (fluid instanceof BlockHitResult) return fluid;
        }
        return mc.hitResult;
    }

    private static boolean holdingFluidItem(BlockHighlight m) {
        if (!m.onlyWhenHoldingAppropriate.get()) return true;
        Minecraft mc = Minecraft.getInstance();
        return fluidItem(mc.player.getItemInHand(InteractionHand.MAIN_HAND))
                || fluidItem(mc.player.getItemInHand(InteractionHand.OFF_HAND));
    }

    private static boolean fluidItem(ItemStack stack) {
        return stack.is(Items.BUCKET) || stack.is(Items.LILY_PAD) || stack.getItem() instanceof BoatItem;
    }

    private static HitResult pick(BlockHighlight m, Entity cam, double range, float partial) {
        Vec3 from = cam.getEyePosition(partial);
        Vec3 to = from.add(cam.getViewVector(partial).scale(range));
        ClipContext.Fluid fluids = m.onlySourceBlocks.get() ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.ANY;
        return cam.level().clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, fluids, cam));
    }

    // ---- the target's shape ----

    private static VoxelShape shapeOf(BlockHighlight m, HitResult hit) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getCameraEntity() == null) return Shapes.block();
        if (hit instanceof BlockHitResult block) {
            BlockPos pos = block.getBlockPos();
            BlockState state = mc.level.getBlockState(pos);
            FluidState fluid = mc.level.getFluidState(pos);
            VoxelShape raw = fluid.isEmpty() ? state.getShape(mc.level, pos) : fluid.getShape(mc.level, pos);
            Direction dir = null;
            VoxelShape neighbour = null;
            if (m.connectedBlocks.get()) {
                dir = connected(pos);
                if (dir != null) {
                    BlockPos other = pos.relative(dir);
                    neighbour = mc.level.getBlockState(other).getShape(mc.level, other, CollisionContext.of(mc.getCameraEntity()));
                }
            }
            if (cached != null && pos.equals(keyPos) && state == keyState && raw == keyRaw && dir == keyDir && neighbour == keyNeighbour) {
                return cached;
            }
            keyPos = pos;
            keyState = state;
            keyRaw = raw;
            keyDir = dir;
            keyNeighbour = neighbour;
            keyEntityBox = null;
            VoxelShape shape = raw.isEmpty() ? Shapes.block() : raw;
            if (dir != null) {
                shape = Shapes.join(shape, neighbour.move(dir.getStepX(), dir.getStepY(), dir.getStepZ()), BooleanOp.OR);
            }
            return cached = shape.move(pos.getX(), pos.getY(), pos.getZ());
        }
        if (hit instanceof EntityHitResult eh && m.allowEntities.get() && !eh.getEntity().isInvisible()) {
            Entity e = eh.getEntity();
            float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            AABB local = zeroed(e.getBoundingBox());
            double lift = e instanceof HangingEntity ? 0 : local.maxY / 2F;
            AABB at = local.move(e.getPosition(partial).subtract(local.getCenter()).add(0, lift, 0));
            if (cached != null && at.equals(keyEntityBox)) return cached;
            keyPos = null;
            keyEntityBox = at;
            return cached = Shapes.create(at);
        }
        return Shapes.block();
    }

    /** The other half of a door, bed, double chest or extended piston, as the direction to it. */
    private static Direction connected(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        BlockState state = mc.level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            DoubleBlockHalf half = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            Direction d = half == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN;
            BlockState other = mc.level.getBlockState(pos.relative(d));
            if (other.getBlock().getClass() == state.getBlock().getClass()
                    && other.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && other.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == half.getOtherHalf()) {
                return d;
            }
        }
        if (state.getBlock() instanceof ChestBlock && state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE) {
            Direction d = ChestBlock.getConnectedDirection(state);
            if (mc.level.getBlockState(pos.relative(d)).getBlock() instanceof ChestBlock) return d;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            BedPart part = state.getValue(BlockStateProperties.BED_PART);
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            Direction d = part == BedPart.HEAD ? facing.getOpposite() : facing;
            BlockState other = mc.level.getBlockState(pos.relative(d));
            if (other.hasProperty(BlockStateProperties.BED_PART) && other.getValue(BlockStateProperties.BED_PART) != part) return d;
        }
        if (state.getBlock() instanceof PistonHeadBlock) {
            Direction facing = state.getValue(BlockStateProperties.FACING);
            BlockState base = mc.level.getBlockState(pos.relative(facing.getOpposite()));
            if (base.getBlock() instanceof PistonBaseBlock && base.getValue(BlockStateProperties.FACING) == facing) {
                return facing.getOpposite();
            }
        }
        if (state.getBlock() instanceof PistonBaseBlock && state.getValue(BlockStateProperties.EXTENDED)) {
            Direction facing = state.getValue(BlockStateProperties.FACING);
            BlockState head = mc.level.getBlockState(pos.relative(facing));
            if (head.getBlock() instanceof PistonHeadBlock && head.getValue(BlockStateProperties.FACING) == facing) return facing;
        }
        return null;
    }

    // ---- gliding box and morphing edges ----

    private static void easeBoxAndEdges(BlockHighlight m, HitResult hit, VoxelShape shape) {
        if (shape != boundsOf) {
            boundsOf = shape;
            bounds = shape.bounds();
        }
        AABB t = bounds;
        if (m.doEasing.get()) {
            if (m.updateWhenUnfocused.get() || hit.getType() != HitResult.Type.MISS) {
                double k = easeFactor(m, m.easeSpeed.asFloat());
                box = new AABB(box.minX + (t.minX - box.minX) * k, box.minY + (t.minY - box.minY) * k, box.minZ + (t.minZ - box.minZ) * k,
                        box.maxX + (t.maxX - box.maxX) * k, box.maxY + (t.maxY - box.maxY) * k, box.maxZ + (t.maxZ - box.maxZ) * k);
            }
        } else {
            box = t;
        }
        boolean edges = false, model = false;
        for (int i = 0; i < 3; i++) {
            Shape s = m.layer(i).shape();
            edges |= s == Shape.COLLISION_SHAPE;
            model |= s == Shape.MODEL_SHAPE;
        }
        if (edges) {
            if (shape != edgesOf) {
                edgesOf = shape;
                VoxelShape local = shape.move(-shape.bounds().minX, -shape.bounds().minY, -shape.bounds().minZ);
                List<Seg> targets = new ArrayList<>();
                local.forAllEdges((x1, y1, z1, x2, y2, z2) -> targets.add(new Seg(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2))));
                edgeTargets = targets;
                edgeCenter = local.bounds().getCenter();
            }
            shapeSegs = follow(m, shapeSegs, edgeTargets, edgeCenter);
        }
        if (model) {
            Direction dir = hit instanceof BlockHitResult ? keyDir : null;
            BlockState neighbour = dir != null && Minecraft.getInstance().level != null
                    ? Minecraft.getInstance().level.getBlockState(keyPos.relative(dir)) : null;
            if (shape != modelOf || dir != modelDir || neighbour != modelNeighbour) {
                modelOf = shape;
                modelDir = dir;
                modelNeighbour = neighbour;
                modelTargets = modelEdges(hit, dir);
                AABB b = shape.bounds();
                modelCenter = zeroed(b).getCenter();
            }
            modelSegs = follow(m, modelSegs, modelTargets, modelCenter);
        }
        if (!leaving.isEmpty()) {
            Vec3 c = zeroed(box).getCenter();
            double k = easeFactor(m, m.easeSpeed.asFloat());
            for (Seg s : leaving) {
                s.moveTo(c, c, k);
                s.fade(m, false);
            }
        }
        leaving.removeIf(s -> s.alpha < 1 / 255F);
    }

    /** The block's model edges in the target's local space (each quad's four sides). */
    private static List<Seg> modelEdges(HitResult hit, Direction dir) {
        List<Seg> out = new ArrayList<>();
        if (!(hit instanceof BlockHitResult block)) return out;
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = block.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        RandomSource random = RandomSource.create(0);
        if (dir != null) {
            BlockState other = mc.level.getBlockState(pos.relative(dir));
            addQuads(other, random, positive(dir) ? unit(dir) : Vec3.ZERO, out);
        }
        Vec3 offset;
        try {
            if (dir != null) {
                offset = positive(dir) ? Vec3.ZERO : unit(dir.getOpposite()).subtract(state.getOffset(pos));
            } else {
                AABB b = state.getShape(mc.level, pos).bounds();
                offset = new Vec3(-b.minX, -b.minY, -b.minZ).add(state.getOffset(pos));
            }
        } catch (RuntimeException e) {
            offset = Vec3.ZERO;
        }
        addQuads(state, random, offset, out);
        return out;
    }

    private static void addQuads(BlockState state, RandomSource random, Vec3 o, List<Seg> out) {
        HighlightPlatform.forEachQuad(state, random, (p0, p1, p2, p3) -> {
            Vec3 a = new Vec3(p0.x(), p0.y(), p0.z()).add(o), b = new Vec3(p1.x(), p1.y(), p1.z()).add(o);
            Vec3 c = new Vec3(p2.x(), p2.y(), p2.z()).add(o), d = new Vec3(p3.x(), p3.y(), p3.z()).add(o);
            out.add(new Seg(a, b));
            out.add(new Seg(b, c));
            out.add(new Seg(c, d));
            out.add(new Seg(d, a));
        });
    }

    private static boolean positive(Direction d) {
        return d.getAxisDirection() == Direction.AxisDirection.POSITIVE;
    }

    private static Vec3 unit(Direction d) {
        return new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
    }

    /** Moves the drawn segments toward the targets: new ones grow from the centre, extras leave. */
    private static List<Seg> follow(BlockHighlight m, List<Seg> current, List<Seg> targets, Vec3 center) {
        while (current.size() < targets.size()) current.add(new Seg(center, center));
        while (current.size() > targets.size()) leaving.add(current.remove(current.size() - 1));
        if (!m.doEasing.get()) {
            List<Seg> copy = new ArrayList<>(targets.size());
            for (Seg t : targets) copy.add(new Seg(t.a, t.b));
            return copy;
        }
        double k = easeFactor(m, m.easeSpeed.asFloat());
        for (int i = 0; i < current.size(); i++) {
            Seg s = current.get(i);
            Seg t = targets.get(i);
            s.moveTo(t.a, t.b, k);
            s.fade(m, true);
        }
        return current;
    }

    // ---- fades, scale, thickness and turning ----

    private static void updateFades(BlockHighlight m, HitResult hit) {
        Minecraft mc = Minecraft.getInstance();
        boolean miss = hit.getType() == HitResult.Type.MISS;
        if (hit instanceof EntityHitResult eh && !eh.getEntity().isInvisible()) {
            if (m.allowEntities.get()) {
                if (m.fillEnabled.get()) {
                    for (int s = 0; s < 6; s++) fillFades[s] = in(m, fillFades[s], m.fillCol.alpha());
                }
                for (int i = 0; i < 3; i++) {
                    Layer l = m.layer(i);
                    if (usesFaceFades(l)) {
                        for (int s = 0; s < 6; s++) edgeFades[i][s] = in(m, edgeFades[i][s], l.color.alpha());
                    }
                    edgeAlpha[i] = in(m, edgeAlpha[i], l.color.alpha());
                }
            } else {
                miss = true;
                fadeAllOut(m);
            }
            if (m.rotations.get()) turnTo(m, new Quaternionf());
        } else if (hit instanceof BlockHitResult block) {
            if (mc.level.isEmptyBlock(block.getBlockPos()) || miss) {
                fadeAllOut(m);
            } else {
                for (int i = 0; i < 3; i++) {
                    Layer l = m.layer(i);
                    if (usesFaceFades(l)) {
                        Set<Direction> sides = sides(l.faces(), block);
                        for (Direction d : DIRS) {
                            int s = d.get3DDataValue();
                            edgeFades[i][s] = sides.contains(d) ? in(m, edgeFades[i][s], l.color.alpha()) : out(m, edgeFades[i][s]);
                        }
                    }
                    edgeAlpha[i] = in(m, edgeAlpha[i], l.color.alpha());
                }
                if (m.fillEnabled.get()) {
                    Set<Direction> sides = sides(m.fillType.value(), block);
                    for (Direction d : DIRS) {
                        int s = d.get3DDataValue();
                        fillFades[s] = sides.contains(d) ? in(m, fillFades[s], m.fillCol.alpha()) : out(m, fillFades[s]);
                    }
                }
            }
            if (m.rotations.get()) {
                Direction d = block.getDirection();
                Quaternionf target = d.getRotation();
                if (d.getAxis().isHorizontal()) {
                    lastHorizontal = d;
                } else {
                    target = new Quaternionf(lastHorizontal.getRotation()).rotateX(d == Direction.UP ? -Mth.HALF_PI : Mth.HALF_PI);
                }
                BlockPos pos = block.getBlockPos();
                if (!((VoxelShapeInvoker) mc.level.getBlockState(pos).getShape(mc.level, pos)).duskclient$isCubeLike()) {
                    target = new Quaternionf();
                }
                turnTo(m, target);
            }
        }
        scaleProg = m.scale.get() ? easeF(m, scaleProg, miss ? 0 : 1, m.scaleSpeed.asFloat()) : 1;
        lineProg = m.animateLineThickness.get() ? easeF(m, lineProg, miss ? 0 : 1, m.lineThicknessAnimationSpeed.asFloat()) : 1;
    }

    private static void turnTo(BlockHighlight m, Quaternionf target) {
        rotation.nlerp(target, (float) easeFactor(m, m.rotationSpeed.asFloat()));
    }

    private static boolean usesFaceFades(Layer l) {
        return l.on() && l.shape() == Shape.CLASSIC_BOX;
    }

    private static void fadeAllOut(BlockHighlight m) {
        if (m.fillEnabled.get()) {
            for (int s = 0; s < 6; s++) fillFades[s] = out(m, fillFades[s]);
        }
        for (int i = 0; i < 3; i++) {
            if (usesFaceFades(m.layer(i))) {
                // six passes a frame, as CBH does: classic outlines leave faster than they arrive
                for (int pass = 0; pass < 6; pass++) {
                    for (int s = 0; s < 6; s++) edgeFades[i][s] = out(m, edgeFades[i][s]);
                }
            }
            edgeAlpha[i] = out(m, edgeAlpha[i]);
        }
    }

    private static Set<Direction> sides(Faces faces, BlockHitResult hit) {
        return switch (faces) {
            case LOOKAT -> EnumSet.of(hit.getDirection());
            case ALL -> EnumSet.allOf(Direction.class);
            case CONCEALED -> concealed(hit.getBlockPos());
            case AIR_EXPOSED -> EnumSet.complementOf(concealed(hit.getBlockPos()));
        };
    }

    private static EnumSet<Direction> concealed(BlockPos pos) {
        EnumSet<Direction> out = EnumSet.allOf(Direction.class);
        for (Direction d : DIRS) {
            if (open(pos.relative(d))) out.remove(d);
        }
        return out;
    }

    /** Air, or a fluid that isn't sitting inside a block that could hold it. */
    private static boolean open(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        BlockState state = mc.level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && !state.getValue(BlockStateProperties.WATERLOGGED)
                && !mc.level.getFluidState(pos).isEmpty()) {
            return true;
        }
        return state.isAir();
    }

    private static float in(BlockHighlight m, float current, int alpha) {
        return m.fadeIn.get() ? easeF(m, current, alpha, m.fadeInSpeed.asFloat()) : alpha;
    }

    private static float out(BlockHighlight m, float current) {
        return m.fadeOut.get() ? easeF(m, current, 0, m.fadeOutSpeed.asFloat()) : 0;
    }

    private static double easeFactor(BlockHighlight m, float speed) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.enableVsync().get() || !m.improvedEasing.get()) {
            return 1 - Math.exp(-(1F / mc.getFps()) * speed);
        }
        return 1 - Math.exp(-(mc.getFrameTimeNs() / 1e9) * speed);
    }

    private static float easeF(BlockHighlight m, float from, float to, float speed) {
        return (float) (from + (to - from) * easeFactor(m, speed));
    }

    // ---- crystal helper ----

    /** On obsidian or bedrock with something in the way of a crystal. */
    private static boolean obstructed(BlockHighlight m, HitResult hit) {
        if (!m.crystalHelper.get() || !(hit instanceof BlockHitResult block) || hit.getType() == HitResult.Type.MISS) return false;
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = block.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) return false;
        BlockPos above = pos.above();
        if (!mc.level.isEmptyBlock(above)) return true;
        AABB room = new AABB(above.getX(), above.getY(), above.getZ(), above.getX() + 1, above.getY() + 2, above.getZ() + 1);
        return !mc.level.getEntities((Entity) null, room).isEmpty();
    }

    // ---- colours ----

    /** The two gradient ends as 0xRRGGBB. */
    private static int[] colours(Paint p, boolean obstructed, int crystal) {
        if (obstructed) return new int[] {crystal & 0xFFFFFF, crystal & 0xFFFFFF};
        if (p.rainbow()) return new int[] {rainbow(p, false), rainbow(p, true)};
        return new int[] {p.rgb1(), p.rgb2()};
    }

    private static int rainbow(Paint p, boolean second) {
        float hue = (Mth.ceil(Util.getMillis() + (second ? p.delay() : 0)) * p.speed() / 50) % 360;
        return hsb(hue / 360F, p.saturation(), p.brightness());
    }

    /** Hue, saturation and brightness to 0xRRGGBB, rounding to the nearest channel value. */
    private static int hsb(float h, float s, float v) {
        float r, g, b;
        if (s == 0) {
            r = g = b = v;
        } else {
            float sector = (h - (float) Math.floor(h)) * 6F;
            float f = sector - (float) Math.floor(sector);
            float p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
            switch ((int) sector) {
                case 0 -> { r = v; g = t; b = p; }
                case 1 -> { r = q; g = v; b = p; }
                case 2 -> { r = p; g = v; b = t; }
                case 3 -> { r = p; g = q; b = v; }
                case 4 -> { r = t; g = p; b = v; }
                default -> { r = v; g = p; b = q; }
            }
        }
        return (int) (r * 255 + 0.5F) << 16 | (int) (g * 255 + 0.5F) << 8 | (int) (b * 255 + 0.5F);
    }

    private static int mix(int a, int b, float t) {
        int r = Mth.clamp(Mth.lerpInt(t, a >> 16 & 0xFF, b >> 16 & 0xFF), 0, 255);
        int g = Mth.clamp(Mth.lerpInt(t, a >> 8 & 0xFF, b >> 8 & 0xFF), 0, 255);
        int bl = Mth.clamp(Mth.lerpInt(t, a & 0xFF, b & 0xFF), 0, 255);
        return r << 16 | g << 8 | bl;
    }

    // ---- drawing ----

    private static void draw(BlockHighlight m, PoseStack pose, boolean obstructed) {
        if (m.fillEnabled.get()) {
            Profiler.get().push("drawFill");
            try {
                drawFill(m, pose, obstructed);
            } finally {
                Profiler.get().pop();
            }
        }
        if (!m.primary.on()) return;
        Depth batch = null;
        boolean open = false;
        VertexConsumer out = null;
        for (int i = 0; i < 3; i++) {
            Layer l = m.layer(i);
            if (!l.on()) continue;
            if (open && l.depth() != batch) {
                HighlightGpu.end(true, batch);
                open = false;
            }
            if (!open) {
                batch = l.depth();
                out = HighlightGpu.begin(true, batch);
                open = true;
            }
            drawLayer(m, pose, l, i, obstructed, out);
        }
        if (open) HighlightGpu.end(true, batch);
    }

    private static void drawFill(BlockHighlight m, PoseStack pose, boolean obstructed) {
        Depth depth = m.fillDepthTest.value();
        boolean nudge = depth != Depth.ALWAYS_PASS && m.fillExpandBlocks.asFloat() == 0 && m.fillExpandPercent.asFloat() == 1;
        AABB b = box.inflate(nudge ? 0.00005 : 0);
        place(m, pose, b, m.fillExpandBlocks.asFloat(), m.fillExpandPercent.asFloat());
        VertexConsumer out = HighlightGpu.begin(false, depth);
        quads(pose.last(), out, zeroed(b), colours(m.fillCol, obstructed, m.crystalHelperFillColor.argb()), fillFades);
        HighlightGpu.end(false, depth);
        pose.popPose();
    }

    /** Moves the pose onto the box, then grows, scales and turns it about the box's centre. */
    private static void place(BlockHighlight m, PoseStack pose, AABB b, float growBlocks, float growPercent) {
        Vec3 cam = HighlightPlatform.cameraPos();
        pose.pushPose();
        pose.translate(b.minX - cam.x, b.minY - cam.y, b.minZ - cam.z);
        Vec3 c = zeroed(b).getCenter();
        pose.translate(c.x, c.y, c.z);
        if (m.rotations.get()) pose.rotateAround(rotation, 0, 0, 0);
        AABB grown = b.inflate(growBlocks);
        pose.scale(ratio(grown.getXsize(), b.getXsize()), ratio(grown.getYsize(), b.getYsize()), ratio(grown.getZsize(), b.getZsize()));
        pose.scale(growPercent, growPercent, growPercent);
        pose.scale(scaleProg, scaleProg, scaleProg);
        pose.translate(-c.x, -c.y, -c.z);
    }

    private static float ratio(double grown, double size) {
        return size == 0 ? 1 : (float) (grown / size);
    }

    private static void drawLayer(BlockHighlight m, PoseStack pose, Layer l, int i, boolean obstructed, VertexConsumer out) {
        place(m, pose, box, l.expandBlocks(), l.expandPercent());
        AABB local = zeroed(box);
        int[] cols = colours(l.color, obstructed, m.crystalHelperLineColor.argb());
        if (l.shape() != Shape.CLASSIC_BOX) {
            int alpha = Math.round(edgeAlpha[i]);
            if (alpha >= 1) {
                PoseStack.Pose p = pose.last();
                Vec3 min = local.getMinPosition();
                double span = min.distanceTo(local.getMaxPosition());
                List<Seg> src = l.shape() == Shape.COLLISION_SHAPE ? shapeSegs : modelSegs;
                for (Seg s : src) drawSeg(s, p, out, l, cols, min, span, alpha);
                for (Seg s : leaving) drawSeg(s, p, out, l, cols, min, span, alpha);
            }
        } else {
            boxLines(pose.last(), out, local, cols, edgeFades[i], l.width() * lineProg, l);
        }
        pose.popPose();
    }

    private static void drawSeg(Seg s, PoseStack.Pose p, VertexConsumer out, Layer l, int[] cols, Vec3 min, double span, int alpha) {
        int a = cols[0], b = cols[1];
        if (a != b) {
            a = mix(cols[0], cols[1], (float) (min.distanceTo(s.a) / span));
            b = mix(cols[0], cols[1], (float) (min.distanceTo(s.b) / span));
        }
        int alphaNow = Math.round(alpha * s.alpha);
        if (alphaNow < 1) return;
        float dx = (float) (s.b.x - s.a.x), dy = (float) (s.b.y - s.a.y), dz = (float) (s.b.z - s.a.z);
        float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        line(p, out, (float) s.a.x, (float) s.a.y, (float) s.a.z, (float) s.b.x, (float) s.b.y, (float) s.b.z,
                a, b, alphaNow, dx / len, dy / len, dz / len, l.width(), l);
    }

    /** The box's twelve edges, each as bright as the brighter of its two faces. */
    private static void boxLines(PoseStack.Pose p, VertexConsumer out, AABB b, int[] cols, float[] fades, float width, Layer l) {
        float x1 = (float) b.minX, y1 = (float) b.minY, z1 = (float) b.minZ;
        float x2 = (float) b.maxX, y2 = (float) b.maxY, z2 = (float) b.maxZ;
        Vector3f min = new Vector3f(x1, y1, z1);
        float diag = min.distance(x2, y2, z2);
        int c111 = corner(cols, min, diag, x1, y1, z1), c211 = corner(cols, min, diag, x2, y1, z1);
        int c112 = corner(cols, min, diag, x1, y1, z2), c212 = corner(cols, min, diag, x2, y1, z2);
        int c121 = corner(cols, min, diag, x1, y2, z1), c221 = corner(cols, min, diag, x2, y2, z1);
        int c122 = corner(cols, min, diag, x1, y2, z2), c222 = corner(cols, min, diag, x2, y2, z2);
        // faces: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east
        line(p, out, x1, y1, z1, x2, y1, z1, c111, c211, edge(fades, 0, 2), 1, 0, 0, width, l);
        line(p, out, x1, y1, z1, x1, y1, z2, c111, c112, edge(fades, 4, 0), 0, 0, 1, width, l);
        line(p, out, x2, y1, z1, x2, y1, z2, c211, c212, edge(fades, 5, 0), 0, 0, 1, width, l);
        line(p, out, x1, y1, z2, x2, y1, z2, c112, c212, edge(fades, 3, 0), 1, 0, 0, width, l);

        line(p, out, x1, y1, z2, x1, y2, z2, c112, c122, edge(fades, 3, 4), 0, 1, 0, width, l);
        line(p, out, x1, y1, z1, x1, y2, z1, c111, c121, edge(fades, 2, 4), 0, 1, 0, width, l);
        line(p, out, x2, y1, z2, x2, y2, z2, c212, c222, edge(fades, 3, 5), 0, -1, 0, width, l);
        line(p, out, x2, y1, z1, x2, y2, z1, c211, c221, edge(fades, 2, 5), 0, 1, 0, width, l);

        line(p, out, x1, y2, z1, x2, y2, z1, c121, c221, edge(fades, 2, 1), 1, 0, 0, width, l);
        line(p, out, x1, y2, z1, x1, y2, z2, c121, c122, edge(fades, 4, 1), 0, 0, 1, width, l);
        line(p, out, x2, y2, z1, x2, y2, z2, c221, c222, edge(fades, 5, 1), 0, 0, 1, width, l);
        line(p, out, x1, y2, z2, x2, y2, z2, c122, c222, edge(fades, 3, 1), 1, 0, 0, width, l);
    }

    private static int corner(int[] cols, Vector3f min, float diag, float x, float y, float z) {
        return mix(cols[0], cols[1], min.distance(x, y, z) / diag);
    }

    private static int edge(float[] fades, int a, int b) {
        return Math.round(Math.max(fades[a], fades[b]));
    }

    /**
     * One edge: a single line, or two lines when there's a gap in the middle,
     * pulled back from the corners and tapered between corner and middle thickness.
     */
    private static void line(PoseStack.Pose p, VertexConsumer out, float x1, float y1, float z1, float x2, float y2, float z2,
                             int rgb1, int rgb2, int alpha, float nx, float ny, float nz, float width, Layer l) {
        if (alpha < 1) return;
        float center = l.cutCenter(), corner = l.cutCorner(), outer = l.outer(), inner = l.inner();
        if (center == 0 && corner == 0) {
            vertex(p, out, x1, y1, z1, rgb1, alpha, nx, ny, nz, width);
            vertex(p, out, x2, y2, z2, rgb2, alpha, nx, ny, nz, width);
            return;
        }
        Vector3f a = new Vector3f(x1, y1, z1), b = new Vector3f(x2, y2, z2);
        Vector3f startOuter = a.lerp(b, corner / 2, new Vector3f());
        Vector3f endOuter = b.lerp(a, corner / 2, new Vector3f());
        if (center == 0 && outer == 0 && inner == 0) {
            vertex(p, out, startOuter, rgb1, alpha, nx, ny, nz, width * outer);
            vertex(p, out, endOuter, rgb2, alpha, nx, ny, nz, width * outer);
            return;
        }
        Vector3f mid = a.lerp(b, 0.5F, new Vector3f());
        Vector3f startInner = mid.lerp(a, center, new Vector3f());
        Vector3f endInner = mid.lerp(b, center, new Vector3f());
        float t = Math.clamp(startInner.distance(startOuter) / startOuter.distance(endOuter), 0, 1);
        vertex(p, out, startOuter, rgb1, alpha, nx, ny, nz, width * outer);
        vertex(p, out, startInner, between(rgb1, rgb2, t), alpha, nx, ny, nz, width * inner);
        vertex(p, out, endInner, between(rgb1, rgb2, 1 - t), alpha, nx, ny, nz, width * inner);
        vertex(p, out, endOuter, rgb2, alpha, nx, ny, nz, width * outer);
    }

    private static int between(int a, int b, float t) {
        int r = (int) Mth.lerp(t, a >> 16 & 0xFF, b >> 16 & 0xFF);
        int g = (int) Mth.lerp(t, a >> 8 & 0xFF, b >> 8 & 0xFF);
        int bl = (int) Mth.lerp(t, a & 0xFF, b & 0xFF);
        return r << 16 | g << 8 | bl;
    }

    private static void vertex(PoseStack.Pose p, VertexConsumer out, Vector3f v, int rgb, int alpha, float nx, float ny, float nz, float width) {
        vertex(p, out, v.x, v.y, v.z, rgb, alpha, nx, ny, nz, width);
    }

    private static void vertex(PoseStack.Pose p, VertexConsumer out, float x, float y, float z, int rgb, int alpha,
                               float nx, float ny, float nz, float width) {
        out.addVertex(p, x, y, z).setColor(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, alpha)
                .setNormal(p, nx, ny, nz).setLineWidth(width);
    }

    /** The box's six faces, each faded on its own, coloured by distance from the low corner. */
    private static void quads(PoseStack.Pose p, VertexConsumer out, AABB b, int[] cols, float[] fades) {
        float x1 = (float) b.minX, y1 = (float) b.minY, z1 = (float) b.minZ;
        float x2 = (float) b.maxX, y2 = (float) b.maxY, z2 = (float) b.maxZ;
        Vector3f min = new Vector3f(x1, y1, z1);
        float span = min.distance(x2, y2, z2);
        float[][] faces = {
                {x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2},
                {x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1},
                {x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1},
                {x2, y1, z2, x2, y2, z2, x1, y2, z2, x1, y1, z2},
                {x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1},
                {x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2},
        };
        for (int f = 0; f < 6; f++) {
            int alpha = Math.round(fades[f]);
            if (alpha < 1) continue;
            float[] v = faces[f];
            for (int k = 3; k >= 0; k--) {
                float x = v[k * 3], y = v[k * 3 + 1], z = v[k * 3 + 2];
                int rgb = mix(cols[0], cols[1], min.distance(x, y, z) / span);
                out.addVertex(p, x, y, z).setColor(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, alpha);
            }
        }
    }

    private static AABB zeroed(AABB b) {
        return b.move(-b.minX, -b.minY, -b.minZ);
    }

    /** One drawn edge, gliding toward its target and fading in or out. */
    private static final class Seg {
        Vec3 a, b;
        float alpha = 1;

        Seg(Vec3 a, Vec3 b) {
            this.a = a;
            this.b = b;
        }

        void moveTo(Vec3 ta, Vec3 tb, double k) {
            if (!a.equals(ta)) a = a.lerp(ta, k);
            if (!b.equals(tb)) b = b.lerp(tb, k);
        }

        void fade(BlockHighlight m, boolean in) {
            if (in) {
                if (alpha == 1) return;
                alpha = m.fadeIn.get() ? easeF(m, alpha, 1, m.fadeInSpeed.asFloat()) : 1;
            } else {
                alpha = m.fadeOut.get() ? easeF(m, alpha, 0, m.fadeOutSpeed.asFloat()) : 0;
            }
        }
    }
}
