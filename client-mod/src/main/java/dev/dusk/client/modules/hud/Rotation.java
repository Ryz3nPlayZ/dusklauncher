package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import net.minecraft.util.Mth;

public class Rotation extends TextHud {
    public Rotation() {
        super("rotation", "Yaw / Pitch", "Camera yaw and pitch in degrees.");
        setPosition(5, 71);
    }

    private static String format(float yaw, float pitch) {
        return "Yaw: " + Fmt.fixed(yaw, 1) + " Pitch: " + Fmt.fixed(pitch, 1);
    }

    @Override
    protected String text(HudContext ctx) {
        var p = ctx.player();
        return p == null ? null : format(Mth.wrapDegrees(p.getYRot()), p.getXRot());
    }

    @Override
    protected String sample() {
        return format(0, 0);
    }
}
