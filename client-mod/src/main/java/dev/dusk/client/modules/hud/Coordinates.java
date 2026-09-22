package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.IntSetting;

public class Coordinates extends TextHud {
    private final IntSetting decimals = add(new IntSetting("decimals", "Decimals", 0, 0, 3));

    public Coordinates() {
        super("coords", "Coordinates", "XYZ", "Your position.");
        setPosition(5, 38);
        setEnabled(true);
    }

    @Override
    protected String value(HudContext ctx) {
        var p = ctx.player();
        if (p == null) return null;
        int d = decimals.get();
        return Fmt.fixed(p.getX(), d) + ", " + Fmt.fixed(p.getY(), d) + ", " + Fmt.fixed(p.getZ(), d);
    }

    @Override
    protected String sample() {
        return "0, 64, 0";
    }
}
