package dev.dusk.client.modules.render;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.KeySetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Freecam: F4 lifts the camera off your player so you can fly it around
 * while you stand still. Movement keys steer the camera, your player gets no
 * input and can't attack or use items. The camera collides with blocks, so
 * it can't see through walls; "Fly through blocks" lifts that in
 * singleplayer only. Ends on death, world change, or F4 again.
 * Independent implementation.
 */
public class Freecam extends Module {
    private static Freecam active;

    private final KeySetting toggleKey = add(new KeySetting("freecam", "Freecam key", InputConstants.KEY_F4), "Keybinds");
    private final IntSetting speed = add(new IntSetting("speed", "Fly speed", 10, 1, 50, 1, " b/s"), "Movement");
    private final BoolSetting followLook = add(new BoolSetting("followLook", "Fly where you look", false), "Movement");
    private final BoolSetting noclip = add(new BoolSetting("noclip", "Fly through blocks (singleplayer)", false), "Movement");

    private Level level;
    private double x, y, z, prevX, prevY, prevZ;
    private Vec3 velocity = Vec3.ZERO;
    private float yaw, pitch;
    private boolean savedSmartCull;

    public Freecam() {
        super("freecam", "Freecam", Category.RENDER,
                "Press F4 to fly the camera around while your player stays put.");
        setEnabled(true);
    }

    /** The module while it owns the camera, else null. */
    public static Freecam active() { return active; }

    /** True for the attack, use and pick keys while the camera is detached. */
    public static boolean blocksKey(KeyMapping key) {
        if (active == null) return false;
        Options o = Minecraft.getInstance().options;
        return key == o.keyAttack || key == o.keyUse || key == o.keyPickItem;
    }

    public float yaw() { return yaw; }

    public float pitch() { return pitch; }

    public double x(float partialTick) { return Mth.lerp(partialTick, prevX, x); }

    public double y(float partialTick) { return Mth.lerp(partialTick, prevY, y); }

    public double z(float partialTick) { return Mth.lerp(partialTick, prevZ, z); }

    /** Mouse movement, in the units {@code Entity.turn} takes. */
    public void turn(double dx, double dy) {
        yaw += (float) dx * 0.15F;
        pitch = Mth.clamp(pitch + (float) dy * 0.15F, -90f, 90f);
    }

    /** The toggle key and the camera's movement, every client tick. */
    public void tickKeys() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        while (toggleKey.mapping().consumeClick()) {
            // a key shared with freelook stays freelook's, as it was before freecam
            if (player == null || !enabled() || Freelook.usesKey(toggleKey.mapping())) continue;
            if (active == this) stop(); else start(mc, player);
            Compat.actionBar(player, Component.literal(active == this ? "Freecam on" : "Freecam off"));
        }
        if (active != this) return;
        if (player == null || !enabled() || !player.isAlive() || mc.level != level) {
            stop();
            return;
        }
        move(mc, player);
    }

    @Override
    protected void onDisable() {
        if (active == this) stop();
    }

    private void start(Minecraft mc, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        x = prevX = eye.x;
        y = prevY = eye.y;
        z = prevZ = eye.z;
        yaw = player.getYRot();
        pitch = player.getXRot();
        velocity = Vec3.ZERO;
        level = mc.level;
        savedSmartCull = mc.smartCull;
        active = this;
    }

    private void stop() {
        if (active != this) return;
        active = null;
        level = null;
        Minecraft.getInstance().smartCull = savedSmartCull;
    }

    private boolean passesThroughBlocks(Minecraft mc) {
        return noclip.get() && mc.isLocalServer();
    }

    private void move(Minecraft mc, LocalPlayer player) {
        Options o = mc.options;
        float forward = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        float left = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
        float up = (o.keyJump.isDown() ? 1 : 0) - (o.keyShift.isDown() ? 1 : 0);

        float yawRad = yaw * Mth.DEG_TO_RAD;
        float sin = Mth.sin(yawRad), cos = Mth.cos(yawRad);
        Vec3 ahead = followLook.get()
                ? Vec3.directionFromRotation(pitch, yaw)
                : new Vec3(-sin, 0, cos);
        Vec3 wish = ahead.scale(forward)
                .add(cos * left, 0, sin * left)
                .add(0, up, 0);
        double perTick = speed.get() / 20.0 * (o.keySprint.isDown() ? 2.5 : 1);
        Vec3 target = wish.lengthSqr() > 1e-6 ? wish.normalize().scale(perTick) : Vec3.ZERO;
        velocity = velocity.add(target.subtract(velocity).scale(0.45));
        if (velocity.lengthSqr() < 1e-6) velocity = Vec3.ZERO;

        Vec3 step = velocity;
        boolean through = passesThroughBlocks(mc);
        if (!through && step != Vec3.ZERO) {
            AABB box = AABB.ofSize(new Vec3(x, y, z), 0.3, 0.3, 0.3);
            step = Entity.collideBoundingBox(player, step, box, level, List.of());
            velocity = new Vec3(step.x == velocity.x ? velocity.x : 0,
                    step.y == velocity.y ? velocity.y : 0,
                    step.z == velocity.z ? velocity.z : 0);
        }
        mc.smartCull = !through && savedSmartCull;
        prevX = x;
        prevY = y;
        prevZ = z;
        x += step.x;
        y += step.y;
        z += step.z;
    }
}
