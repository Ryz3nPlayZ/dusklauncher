package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.ClickTracker;
import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Flex-HUD's Reach: how far away the last entity you hit was, back to 0 five seconds later. */
public class Reach extends TextHud {
    private final IntSetting digits = add(new IntSetting("digits", "Decimals", 2, 0, 4));
    private int lastSerial = -1;
    private double reach;
    private long lastHitMs = -1;

    public Reach() {
        super("reach", "Reach", "Distance to the last entity you attacked.");
        setPosition(150, 93);
    }

    @Override
    protected String text(HudContext ctx) {
        var mc = ctx.mc();
        if (mc.player == null) return null;
        int serial = ClickTracker.attackSerial();
        if (serial != lastSerial) {
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
