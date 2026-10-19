package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.ClickTracker;
import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Flex-HUD's Reach: how far away the last entity you hit was, back to 0 five seconds later. */
public class Reach extends TextHud {
    private final IntSetting digits = add(new IntSetting("digits", "Decimals", 2, 0, 4));
    private int lastSerial = -1;
    private double reach;
    private long lastHitMs = -1;

    /** Set by the attack hook, on versions that have one; it then replaces the key-press guess. */
    private static volatile boolean hooked;
    private static volatile double hookedReach;
    private static volatile long hookedAtMs = -1;

    /**
     * Every attack the game sends, whoever made it (a click, another mod), measured where it hit: the
     * crosshair's spot on the target, or else where the look line enters its box, or its nearest point.
     */
    public static void onAttack(Player player, Entity target) {
        if (!(target instanceof LivingEntity)) return;
        Vec3 eye = player.getEyePosition();
        AABB box = target.getBoundingBox();
        Vec3 at = null;
        if (Minecraft.getInstance().hitResult instanceof EntityHitResult hit && hit.getEntity() == target) {
            at = hit.getLocation();
        } else if (!box.contains(eye)) {
            at = box.clip(eye, eye.add(player.getViewVector(1).scale(8))).orElse(null);
        }
        if (at == null) {
            at = new Vec3(Math.clamp(eye.x, box.minX, box.maxX), Math.clamp(eye.y, box.minY, box.maxY),
                    Math.clamp(eye.z, box.minZ, box.maxZ));
        }
        hookedReach = at.distanceTo(eye);
        hookedAtMs = System.currentTimeMillis();
        hooked = true;
    }

    public Reach() {
        super("reach", "Reach", "Distance to the last entity you attacked.");
        setPosition(150, 93);
    }

    @Override
    protected String text(HudContext ctx) {
        var mc = ctx.mc();
        if (mc.player == null) return null;
        int serial = ClickTracker.attackSerial();
        if (hooked) {
            if (hookedAtMs != lastHitMs) {
                reach = hookedReach;
                lastHitMs = hookedAtMs;
            }
        } else if (serial != lastSerial) {
            lastSerial = serial;
            var camera = mc.getCameraEntity();
            if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity && camera != null) {
                Vec3 pos = camera.getPosition(1);
                Vec3 eye = new Vec3(pos.x(), pos.y() + camera.getEyeHeight(mc.player.getPose()), pos.z());
                reach = hit.getLocation().distanceTo(eye);
                lastHitMs = System.currentTimeMillis();
            }
        }
        if (lastHitMs == -1 || System.currentTimeMillis() - lastHitMs > 5000) reach = 0.0;
        return Fmt.fixed(reach, digits.get()) + " blocks";
    }

    @Override
    protected String sample() {
        return Fmt.fixed(0.0, digits.get()) + " blocks";
    }
}
