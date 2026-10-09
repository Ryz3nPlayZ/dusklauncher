package dev.dusk.client.modules.render;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.render.WorldProjection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Light Overlay: a cross on every block near you a monster could spawn on —
 * red where it's dark enough at any time, yellow where only the night is
 * dark enough (the colours Light Overlay and the old NEI overlay use). The
 * test is vanilla's own: a floor monsters may stand on, room for a zombie
 * above it, and block light within the dimension's limit.
 *
 * Only spots you can actually see are marked, so it never shows the shape of
 * a cave through the stone around it.
 */
public class LightOverlay extends Module {
    private static final int RESCAN_TICKS = 10;
    private static final int MAX_SPOTS = 4096;
    private static final int RED = 0xC0FF3030, YELLOW = 0xC0FFD020;

    private static LightOverlay instance;

    private final IntSetting radius = add(new IntSetting("radius", "Range", 12, 4, 24, 1, " blocks"));
    private final BoolSetting nightOnly = add(new BoolSetting("nightOnly", "Mark spots only dark at night (yellow)", true));

    /** packed BlockPos of each spot; spotRed says which colour */
    private long[] spots = new long[256];
    private boolean[] spotRed = new boolean[256];
    private int count;
    private int untilScan;
    private final float[] a = new float[2], b = new float[2], c = new float[2], d = new float[2];

    public LightOverlay() {
        super("lightoverlay", "Light Overlay", Category.RENDER,
                "Crosses on blocks mobs can spawn on: red any time, yellow at night.");
        instance = this;
    }

    @Override
    protected void onDisable() {
        count = 0;
        untilScan = 0;
    }

    @Override
    public void tick() {
        if (--untilScan > 0) return;
        untilScan = RESCAN_TICKS;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        count = 0;
        if (level == null || player == null) return;
        scan(level, player);
    }

    private void scan(ClientLevel level, LocalPlayer player) {
        int r = radius.get();
        int limit = level.dimensionType().monsterSpawnBlockLightLimit();
        BlockPos origin = player.blockPosition();
        Vec3 eye = player.getEyePosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        EntityType<?> zombie = Compat.spawnTestMob();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                for (int dy = -r; dy <= r; dy++) {
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = level.getBlockState(pos);
                    if (!NaturalSpawner.isValidEmptySpawnBlock(level, pos, state, state.getFluidState(), zombie)) continue;
                    below.set(pos.getX(), pos.getY() - 1, pos.getZ());
                    if (!level.getBlockState(below).isValidSpawn(level, below, zombie)) continue;
                    BlockPos up = pos.above();
                    BlockState upState = level.getBlockState(up);
                    if (!NaturalSpawner.isValidEmptySpawnBlock(level, up, upState, upState.getFluidState(), zombie)) continue;
                    if (level.getBrightness(LightLayer.BLOCK, pos) > limit) continue;
                    boolean red = level.getBrightness(LightLayer.SKY, pos) == 0;
                    if (!red && !nightOnly.get()) continue;
                    if (!visible(level, player, eye, pos)) continue;
                    add(pos.asLong(), red);
                    if (count >= MAX_SPOTS) return;
                }
            }
        }
    }

    /** Nothing solid between your eyes and the floor of {@code pos}. */
    private static boolean visible(ClientLevel level, LocalPlayer player, Vec3 eye, BlockPos pos) {
        Vec3 target = new Vec3(pos.getX() + 0.5, pos.getY() + 0.05, pos.getZ() + 0.5);
        BlockHitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos) || hit.getBlockPos().equals(pos.below());
    }

    private void add(long packed, boolean red) {
        if (count == spots.length) {
            spots = java.util.Arrays.copyOf(spots, count * 2);
            spotRed = java.util.Arrays.copyOf(spotRed, count * 2);
        }
        spots[count] = packed;
        spotRed[count] = red;
        count++;
    }

    @Nullable
    private static LightOverlay active() {
        LightOverlay m = instance;
        return m != null && m.enabled() ? m : null;
    }

    /** Drawn under the HUD, through the camera of the frame just rendered. */
    public static void draw(Canvas canvas, HudContext ctx) {
        LightOverlay m = active();
        if (m == null || m.count == 0 || !WorldProjection.ready()) return;
        m.drawSpots(canvas, ctx);
    }

    private void drawSpots(Canvas canvas, HudContext ctx) {
        int w = ctx.width(), h = ctx.height();
        for (int i = 0; i < count; i++) {
            long p = spots[i];
            double x = BlockPos.getX(p), y = BlockPos.getY(p) + 0.02, z = BlockPos.getZ(p);
            // the four corners of the floor, as two diagonals
            if (!WorldProjection.project(x + 0.1, y, z + 0.1, w, h, a)) continue;
            if (!WorldProjection.project(x + 0.9, y, z + 0.9, w, h, b)) continue;
            if (!WorldProjection.project(x + 0.9, y, z + 0.1, w, h, c)) continue;
            if (!WorldProjection.project(x + 0.1, y, z + 0.9, w, h, d)) continue;
            if (Math.max(Math.max(a[0], b[0]), Math.max(c[0], d[0])) < 0 || Math.min(Math.min(a[0], b[0]), Math.min(c[0], d[0])) > w) continue;
            if (Math.max(Math.max(a[1], b[1]), Math.max(c[1], d[1])) < 0 || Math.min(Math.min(a[1], b[1]), Math.min(c[1], d[1])) > h) continue;
            int color = spotRed[i] ? RED : YELLOW;
            canvas.line(a[0], a[1], b[0], b[1], 1, color);
            canvas.line(c[0], c[1], d[0], d[1], 1, color);
        }
    }
}
