package dev.dusk.client.render.thirdperson;

import dev.dusk.client.modules.render.FancyThirdPerson;
import dev.dusk.client.modules.render.Freelook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * The camera behind {@link FancyThirdPerson}, after Leawind's Third Person
 * (MIT, see NOTICE). The camera keeps its own yaw and pitch, which the mouse
 * turns. Each frame it puts the player's eye at an offset from the screen
 * centre, pulls in where a wall is in the way, and finds what the crosshair
 * points at; the player then turns toward where it walks, or toward that
 * point while hitting, using or aiming. The interaction picks aim from the
 * eye at that point too, so what you hit is what the crosshair shows.
 * While riding or gliding the player steers as usual and the camera follows.
 */
public final class FancyCamera {
    private static final double AIM_RANGE = 128, ENTITY_RANGE = 48, WALL_MARGIN = 0.1;

    private static boolean active, driven, aiming, moving;
    private static float camYaw, camPitch, moveYaw;
    private static double cx, cy, cz, side, up, dist;
    private static long lastNanos;
    private static double outX, outY, outZ;
    private static Vec3 aimPoint;

    private FancyCamera() {}

    public static float yaw() { return camYaw; }
    public static float pitch() { return camPitch; }
    public static double x() { return outX; }
    public static double y() { return outY; }
    public static double z() { return outZ; }

    /** Mouse movement; true when it turned the camera instead of the player. */
    public static boolean turn(double dx, double dy) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (FancyThirdPerson.active() == null || p == null || drivesItself(p)) return false;
        if (!active) enter(p);
        camYaw += (float) dx * 0.15F;
        camPitch = Mth.clamp(camPitch + (float) dy * 0.15F, -90F, 90F);
        return true;
    }

    /**
     * Places the camera for this frame. {@code eyeY} is the smoothed eye
     * height vanilla uses. False leaves vanilla's camera alone.
     */
    public static boolean place(Entity entity, float pt, double eyeHeight) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        FancyThirdPerson m = FancyThirdPerson.active();
        if (m == null || p == null || entity != p) {
            if (active) exit(p);
            return false;
        }
        if (!active) enter(p);
        long now = System.nanoTime();
        double dt = Mth.clamp((now - lastNanos) / 1e9, 0, 0.25);
        lastNanos = now;

        driven = drivesItself(p);
        if (driven) {
            camYaw = p.getViewYRot(pt);
            camPitch = p.getViewXRot(pt);
        }
        aiming = isAiming(p);

        // the point the camera turns round: the eye, smoothed a little so steps and sneaking glide
        double ex = Mth.lerp(pt, p.xo, p.getX());
        double ey = Mth.lerp(pt, p.yo, p.getY()) + eyeHeight;
        double ez = Mth.lerp(pt, p.zo, p.getZ());
        double targetDist = aiming && m.aimZoom() ? 0.6 : m.distance();
        if (!m.smooth() || Math.abs(ex - cx) + Math.abs(ey - cy) + Math.abs(ez - cz) > 8) {
            cx = ex; cy = ey; cz = ez;
        } else {
            double scale = Math.sqrt(Math.max(dist, 0.1)) * (aiming ? 0.2 : 1);
            double h = blend(dt, 0.025 * scale), v = blend(dt, 0.02 * scale);
            cx += (ex - cx) * h; cy += (ey - cy) * v; cz += (ez - cz) * h;
        }
        // screen offsets (fractions of half the screen), squeezed toward the centre looking straight up or down
        int sign = m.shoulderSign();
        double squeeze = Mth.clamp((90 - Math.abs(camPitch)) / 10.0, 0, 1);
        double targetSide = -sign * m.sideOffset() * (aiming ? 2 : 1) * squeeze;
        double targetUp = -m.heightOffset() * (aiming ? 1.6 : 1) * squeeze;
        double ho = blend(dt, aiming ? 0.03 : 0.08);
        side += (targetSide - side) * ho;
        up += (targetUp - up) * ho;
        dist += (targetDist - dist) * blend(dt, aiming ? 0.04 : 0.15);

        // camera basis from its own rotation
        double yr = Math.toRadians(camYaw), pr = Math.toRadians(camPitch);
        double fx = -Math.sin(yr) * Math.cos(pr), fy = -Math.sin(pr), fz = Math.cos(yr) * Math.cos(pr);
        double lx = Math.cos(yr), lz = Math.sin(yr);
        double ux = fy * lz, uy = fz * lx - fx * lz, uz = -fy * lx; // forward x left
        double tanV = Math.tan(Math.toRadians(mc.options.fov().get()) / 2);
        double aspect = Math.max(1, mc.getWindow().getWidth()) / (double) Math.max(1, mc.getWindow().getHeight());
        double tanH = tanV * aspect;
        // a ray through the screen point (side, up); the eye sits on it, dist ahead of the camera
        double rx = fx - lx * side * tanH + ux * up * tanV;
        double ry = fy + uy * up * tanV;
        double rz = fz - lz * side * tanH + uz * up * tanV;
        double t = clip(p, cx, cy, cz, -rx * dist, -ry * dist, -rz * dist) * dist;
        outX = cx - rx * t;
        outY = cy - ry * t;
        outZ = cz - rz * t;

        aimPoint = aim(p, outX, outY, outZ, fx, fy, fz);
        rotatePlayer(mc, p, m, pt, dt);
        return true;
    }

    /** The direction the interaction picks use from {@code entity}'s eye, or null for its own look. */
    public static Vec3 pickDirection(Entity entity, float pt) {
        if (!active || driven || aimPoint == null || entity != Minecraft.getInstance().player) return null;
        if (FancyThirdPerson.active() == null) return null;
        Vec3 d = aimPoint.subtract(entity.getEyePosition(pt));
        return d.lengthSqr() < 1e-4 ? null : d.normalize();
    }

    /**
     * Movement keys, camera-relative: turns vanilla's (left, forward) impulse
     * into the player's own frame, and remembers which way that is.
     */
    public static Vec2 remapInput(float left, float forward) {
        LocalPlayer p = Minecraft.getInstance().player;
        moving = left != 0 || forward != 0;
        if (!active || driven || p == null || !moving) return new Vec2(left, forward);
        double c = Math.toRadians(camYaw);
        double wx = -forward * Math.sin(c) + left * Math.cos(c);
        double wz = forward * Math.cos(c) + left * Math.sin(c);
        moveYaw = (float) Math.toDegrees(Math.atan2(-wx, wz));
        double py = Math.toRadians(p.getYRot());
        float f = (float) (-wx * Math.sin(py) + wz * Math.cos(py));
        float l = (float) (wx * Math.cos(py) + wz * Math.sin(py));
        return new Vec2(l, f);
    }

    /** Before key presses are handled: face the crosshair point so a click goes where it shows. */
    public static void beforeKeybinds(boolean clicking) {
        Minecraft mc = Minecraft.getInstance();
        FancyThirdPerson m = FancyThirdPerson.active();
        if (!active || driven || m == null || mc.player == null || aimPoint == null) return;
        if (clicking && m.faceTarget()) face(mc.player, aimPoint, 1);
    }

    private static void rotatePlayer(Minecraft mc, LocalPlayer p, FancyThirdPerson m, float pt, double dt) {
        if (driven || aimPoint == null) return;
        boolean using = p.isUsingItem() && !eating(p.getUseItem());
        boolean interacting = aiming || using || mc.options.keyAttack.isDown()
                || mc.options.keyPickItem.isDown() || (mc.options.keyUse.isDown() && !p.isUsingItem());
        if (interacting && m.faceTarget()) {
            face(p, aimPoint, 1);
        } else if (moving) {
            boolean water = p.isSwimming() || p.isInWater() || p.getAbilities().flying;
            float pitch = water ? camPitch : 0;
            double k = blend(dt, p.isSprinting() ? 0.03 : 0.06);
            set(p, p.getYRot() + Mth.wrapDegrees(moveYaw - p.getYRot()) * (float) k,
                    p.getXRot() + (pitch - p.getXRot()) * (float) k);
        } else if (m.lookWhenIdle() && Freelook.looking() == null && !p.isUsingItem()) {
            face(p, aimPoint, blend(dt, 0.1));
        }
    }

    private static void face(LocalPlayer p, Vec3 point, double k) {
        Vec3 d = point.subtract(p.getEyePosition(1));
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        if (h < 1e-4 && Math.abs(d.y) < 1e-4) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, h));
        set(p, p.getYRot() + Mth.wrapDegrees(yaw - p.getYRot()) * (float) k,
                p.getXRot() + (pitch - p.getXRot()) * (float) k);
    }

    /** Sets the rotation outright, old values too, so the frame does not lerp back. */
    private static void set(LocalPlayer p, float yaw, float pitch) {
        pitch = Mth.clamp(pitch, -90F, 90F);
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.yRotO = yaw;
        p.xRotO = pitch;
    }

    /** What the screen centre points at: the nearest entity or block along the camera's ray. */
    private static Vec3 aim(LocalPlayer p, double x, double y, double z, double fx, double fy, double fz) {
        Vec3 from = new Vec3(x, y, z);
        Vec3 to = from.add(fx * AIM_RANGE, fy * AIM_RANGE, fz * AIM_RANGE);
        HitResult block = p.level().clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        double reach = Math.min(ENTITY_RANGE, from.distanceTo(end));
        Vec3 entityEnd = from.add(fx * reach, fy * reach, fz * reach);
        AABB box = new AABB(from.x, from.y, from.z, entityEnd.x, entityEnd.y, entityEnd.z).inflate(1);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(p, from, entityEnd, box,
                e -> e != p && !e.isSpectator() && e.isPickable() && e != p.getVehicle(), reach * reach);
        return hit != null ? hit.getLocation() : end;
    }

    /**
     * How much of the way from the eye to the camera is free of blocks, from
     * eight rays a little apart so the near plane stays out of walls.
     */
    private static double clip(LocalPlayer p, double x, double y, double z, double dx, double dy, double dz) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4) return 0;
        double best = 1;
        for (int i = 0; i < 8; i++) {
            double ox = ((i & 1) * 2 - 1) * WALL_MARGIN, oy = ((i >> 1 & 1) * 2 - 1) * WALL_MARGIN, oz = ((i >> 2 & 1) * 2 - 1) * WALL_MARGIN;
            Vec3 from = new Vec3(x + ox, y + oy, z + oz);
            Vec3 to = new Vec3(x + dx + ox, y + dy + oy, z + dz + oz);
            HitResult hit = p.level().clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
            if (hit.getType() != HitResult.Type.MISS) best = Math.min(best, hit.getLocation().distanceTo(from) / len);
        }
        return best;
    }

    private static boolean isAiming(LocalPlayer p) {
        if (p.isUsingItem()) {
            ItemUseAnimation anim = p.getUseItem().getUseAnimation();
            if (anim == ItemUseAnimation.BOW || anim == ItemUseAnimation.TRIDENT || anim == ItemUseAnimation.SPEAR) return true;
        }
        return charged(p.getMainHandItem()) || charged(p.getOffhandItem());
    }

    private static boolean charged(ItemStack stack) {
        return stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack);
    }

    private static boolean eating(ItemStack stack) {
        ItemUseAnimation anim = stack.getUseAnimation();
        return anim == ItemUseAnimation.EAT || anim == ItemUseAnimation.DRINK;
    }

    private static boolean drivesItself(LocalPlayer p) {
        return p.isPassenger() || p.isFallFlying();
    }

    private static void enter(LocalPlayer p) {
        active = true;
        camYaw = p.getYRot();
        camPitch = p.getXRot();
        Vec3 eye = p.getEyePosition(1);
        cx = eye.x; cy = eye.y; cz = eye.z;
        side = up = dist = 0;
        lastNanos = System.nanoTime();
        aimPoint = null;
    }

    /** Leaving the view: the player looks where the camera did, so first person carries on from there. */
    private static void exit(LocalPlayer p) {
        active = false;
        aimPoint = null;
        if (p != null && !driven) set(p, camYaw, camPitch);
    }

    /** The share of the way to its target an exponential smoother covers in {@code dt} seconds. */
    private static double blend(double dt, double halfLife) {
        return halfLife <= 0 ? 1 : 1 - Math.pow(0.5, dt / halfLife);
    }
}
