package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import net.minecraft.util.Mth;

public class Rotation extends TextHud {
    public Rotation() {
        super("rotation", "Yaw / Pitch", "Rot", "Camera yaw and pitch in degrees.");
        setPosition(5, 71);
    }

    @Override
    protected String value(HudContext ctx) {
        var p = ctx.player();
        if (p == null) return null;
        return Fmt.fixed(Mth.wrapDegrees(p.getYRot()), 1) + " / " + Fmt.fixed(p.getXRot(), 1);
    }

    @Override
    protected String sample() {
        return "0.0 / 0.0";
    }
}
