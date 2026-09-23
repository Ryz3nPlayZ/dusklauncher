package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.Raycast;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Flex-HUD's Distance: how far away the block you are looking at is. */
public class Distance extends TextHud {
    private final IntSetting digits = add(new IntSetting("digits", "Decimals", 0, 0, 4));

    public Distance() {
        super("distance", "Distance", "", "Distance to the block under your crosshair, out to your render distance.");
        setPosition(150, 137);
        showLabel.set(false);
    }

    @Override
    protected String value(HudContext ctx) {
        var mc = ctx.mc();
        var camera = mc.getCameraEntity();
        if (camera == null || mc.player == null) return null;

        HitResult hit = Raycast.hitResult();
        if (hit == null) return null;
        if (hit.getType() == HitResult.Type.MISS) return "[∞]";
        if (hit.getType() != HitResult.Type.BLOCK) return null;

        Vec3 lerpedPos = camera.getPosition(0);
        float eyeHeight = camera.getEyeHeight(mc.player.getPose());
        Vec3 eyePos = new Vec3(lerpedPos.x(), lerpedPos.y() + eyeHeight, lerpedPos.z());
        return "[" + Fmt.fixed(hit.getLocation().distanceTo(eyePos), digits.get()) + "]";
    }

    @Override
    protected String sample() {
        return "[" + Fmt.fixed(20.0, digits.get()) + "]";
    }
}
