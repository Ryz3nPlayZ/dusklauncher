package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.ChoiceSetting;

public class Speed extends TextHud {
    private final ChoiceSetting unit = add(new ChoiceSetting("unit", "Unit", "blocks/s", "blocks/s", "km/h"));

    public Speed() {
        super("speed", "Speed", "Speed", "Horizontal movement speed.");
        setPosition(5, 82);
    }

    @Override
    protected String value(HudContext ctx) {
        var p = ctx.player();
        if (p == null) return null;
        var v = p.getDeltaMovement();
        double bps = Math.hypot(v.x, v.z) * 20;
        return unit.is("km/h") ? Fmt.fixed(bps * 3.6, 1) + " km/h" : Fmt.fixed(bps, 2) + " b/s";
    }

    @Override
    protected String sample() {
        return "0.00 b/s";
    }
}
