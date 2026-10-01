package dev.dusk.client.render.cape;

import dev.dusk.client.modules.render.CapePhysics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A cape as a hanging chain: one node per row of cape pixels, simulated in
 * the world each client tick (gravity, air drag, a travelling ripple while
 * moving) and pinned at the player's shoulders. The body pushes it out, so it
 * drapes over the back instead of passing through it.
 *
 * <p>Each tick the chain is also stored in the body's own frame, in model
 * pixels; rendering blends the last two of those, so it stays smooth at any
 * frame rate while the simulation itself only runs 20 times a second.
 */
public final class CapeSim {
    public static final int SEGMENTS = 16, NODES = SEGMENTS + 1;

    /** World blocks per model pixel for a scale-1 player (the renderer draws players at 15/16). */
    private static final double PX = 0.9375 / 16.0;
    /** Velocity kept per tick, horizontally and vertically: air drag, which is what makes it trail. */
    private static final double KEEP_H = 0.82, KEEP_V = 0.92;
    private static final double GRAVITY = 0.05;
    private static final double CROUCH_TILT = 0.5; // the model's body xRot while crouching
    /** The resting drape's direction per segment, body frame (down, leaning slightly back). */
    private static final double REST_Y = 1 / Math.sqrt(1.01), REST_Z = 0.1 / Math.sqrt(1.01);

    private static final Map<Integer, CapeSim> SIMS = new HashMap<>();

    private final double[] pos = new double[NODES * 3];
    private final double[] prev = new double[NODES * 3];
    private float[] shapeOld = new float[NODES * 3];
    private float[] shapeNew = new float[NODES * 3];
    // this frame's blend, handed to the renderer; rewritten next frame, after the last one was drawn
    private final float[] frame = new float[NODES * 3];
    private final double[] local = new double[3];
    private double ax, ay, az;
    private boolean fresh = true;
    private int stepped;

    private CapeSim() {}

    // ---- registry ------------------------------------------------------------

    /**
     * Steps every nearby player's cape; forgets the ones out of range.
     * {@code sway} (0..1) scales how far each segment swings from the
     * resting drape: 1 is the full simulation, 0 a cape that hangs still.
     */
    public static void tick(Minecraft mc, int range, float flutter, float sway) {
        if (mc.level == null || mc.player == null) {
            SIMS.clear();
            return;
        }
        int now = mc.player.tickCount;
        double rangeSq = (double) range * range;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p.distanceToSqr(mc.player) > rangeSq || posed(p)) continue;
            CapeSim sim = SIMS.computeIfAbsent(p.getId(), id -> new CapeSim());
            sim.step(p, now, flutter, sway);
            sim.stepped = now;
        }
        for (Iterator<CapeSim> it = SIMS.values().iterator(); it.hasNext(); ) {
            if (it.next().stepped != now) it.remove();
        }
    }

    public static void clear() {
        SIMS.clear();
    }

    /**
     * The cape's nodes for this frame in the body's frame, in model pixels
     * from the cape's top edge ({x, y, z} × {@link #NODES}, y down, z
     * backwards), or null to leave the vanilla cape.
     */
    @Nullable
    public static float[] shape(LivingEntity player, float partialTick) {
        if (!CapePhysics.active() || posed(player)) return null;
        CapeSim sim = SIMS.get(player.getId());
        if (sim == null) return null;
        float[] out = sim.frame;
        for (int i = 0; i < out.length; i++) out[i] = sim.shapeOld[i] + (sim.shapeNew[i] - sim.shapeOld[i]) * partialTick;
        return out;
    }

    /** Poses the body frame here doesn't describe (the whole model is turned): vanilla keeps those. */
    private static boolean posed(LivingEntity p) {
        return p.isFallFlying() || p.isVisuallySwimming() || p.isSleeping() || p.isAutoSpinAttack();
    }

    // ---- simulation ------------------------------------------------------------

    private void step(AbstractClientPlayer p, int tick, float flutter, float sway) {
        double scale = p.getScale();
        double px = PX * scale;
        double yaw = Math.toRadians(p.yBodyRot);
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        boolean crouch = p.isCrouching();
        double tilt = crouch ? CROUCH_TILT : 0;
        // the cape's top edge: behind the neck (model y 0, z 2)
        double height = (crouch ? 1.1 : 1.407) * scale;
        double nx = p.getX() + sin * 2 * px, ny = p.getY() + height, nz = p.getZ() - cos * 2 * px;
        double moved = Math.sqrt((nx - ax) * (nx - ax) + (nz - az) * (nz - az));
        if (fresh || moved > 4 || Math.abs(ny - ay) > 4) {
            reset(nx, ny, nz, sin, cos, px);
            moved = 0;
        }
        ax = nx; ay = ny; az = nz;

        // integrate: keep (damped) velocity, fall, and ripple while moving
        double ripple = flutter * Math.min(moved, 0.4) * 0.35 * px;
        for (int i = 1; i < NODES; i++) {
            int k = i * 3;
            double vx = (pos[k] - prev[k]) * KEEP_H;
            double vy = (pos[k + 1] - prev[k + 1]) * KEEP_V;
            double vz = (pos[k + 2] - prev[k + 2]) * KEEP_H;
            prev[k] = pos[k];
            prev[k + 1] = pos[k + 1];
            prev[k + 2] = pos[k + 2];
            double wave = ripple * Math.sin(tick * 0.9 - i * 0.55) * i / SEGMENTS;
            pos[k] += vx + sin * wave;
            pos[k + 1] += vy - GRAVITY + Math.abs(wave) * 0.5;
            pos[k + 2] += vz - cos * wave;
        }
        // an inextensible chain from the pin down, pushed out of the body, twice
        for (int pass = 0; pass < 2; pass++) {
            pos[0] = ax;
            pos[1] = ay;
            pos[2] = az;
            for (int i = 1; i < NODES; i++) {
                int k = i * 3, j = k - 3;
                double dx = pos[k] - pos[j], dy = pos[k + 1] - pos[j + 1], dz = pos[k + 2] - pos[j + 2];
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1e-9) { dx = 0; dy = -1; dz = 0; len = 1; }
                double f = px / len;
                pos[k] = pos[j] + dx * f;
                pos[k + 1] = pos[j + 1] + dy * f;
                pos[k + 2] = pos[j + 2] + dz * f;
                toLocal(pos[k] - ax, pos[k + 1] - ay, pos[k + 2] - az, sin, cos, tilt, local);
                boolean hit = false;
                if (local[2] < 0) { local[2] = 0; hit = true; }       // not into the back
                if (local[1] < 0) { local[1] = 0; hit = true; }       // not above the shoulders
                if (Math.abs(local[0]) > i * px * 0.5) { local[0] = Math.copySign(i * px * 0.5, local[0]); hit = true; }
                if (hit) toWorld(local, sin, cos, tilt, k);
            }
        }

        float[] t = shapeOld;
        shapeOld = shapeNew;
        shapeNew = t;
        // body-frame chain in model pixels; each segment's swing scaled by sway
        double lx = 0, ly = 0, lz = 0, ox = 0, oy = 0, oz = 0;
        for (int i = 0; i < NODES; i++) {
            int k = i * 3;
            toLocal(pos[k] - ax, pos[k + 1] - ay, pos[k + 2] - az, sin, cos, tilt, local);
            double x = local[0] / px, y = local[1] / px, z = local[2] / px;
            if (i > 0) {
                double dx = x - lx, dy = y - ly, dz = z - lz;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double sx = dx * sway, sy = REST_Y * len + (dy - REST_Y * len) * sway, sz = REST_Z * len + (dz - REST_Z * len) * sway;
                double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
                double f = sl < 1e-9 ? 0 : len / sl; // keep the segment's length
                ox += sx * f;
                oy += sy * f;
                oz += sz * f;
            }
            lx = x; ly = y; lz = z;
            shapeNew[k] = (float) ox;
            shapeNew[k + 1] = (float) oy;
            shapeNew[k + 2] = (float) oz;
        }
        if (fresh) System.arraycopy(shapeNew, 0, shapeOld, 0, shapeNew.length);
        fresh = false;
    }

    private void reset(double x, double y, double z, double sin, double cos, double px) {
        for (int i = 0; i < NODES; i++) {
            int k = i * 3;
            double back = i * px * 0.1;
            pos[k] = prev[k] = x + sin * back;
            pos[k + 1] = prev[k + 1] = y - i * px;
            pos[k + 2] = prev[k + 2] = z - cos * back;
        }
        ax = x; ay = y; az = z;
    }

    /**
     * World offset → the body's frame (x to the model's +x, y down, z
     * backwards), undoing the body's yaw and its crouch tilt.
     */
    private static void toLocal(double wx, double wy, double wz, double sin, double cos, double tilt, double[] out) {
        double mx = wx * cos + wz * sin;
        double my = -wy;
        double mz = wx * sin - wz * cos;
        double ct = Math.cos(tilt), st = Math.sin(tilt);
        out[0] = mx;
        out[1] = my * ct + mz * st;
        out[2] = -my * st + mz * ct;
    }

    /** Body-frame offset {@code l} → a world position (pinned at the cape's top edge), written to node {@code k}. */
    private void toWorld(double[] l, double sin, double cos, double tilt, int k) {
        double ct = Math.cos(tilt), st = Math.sin(tilt);
        double my = l[1] * ct - l[2] * st;
        double mz = l[1] * st + l[2] * ct;
        pos[k] = ax + l[0] * cos + mz * sin;
        pos[k + 1] = ay - my;
        pos[k + 2] = az + l[0] * sin - mz * cos;
    }
}
