package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.util.Fmt;
import dev.dusk.client.util.TpsTracker;

public class Tps extends TextHud {
    private static final int RED = 0xFFFF5555, GREEN = 0xFF55FF55;
    private final IntSetting digits = add(new IntSetting("digits", "Decimals", 1, 0, 4));
    private final BoolSetting dynamicColor = add(new BoolSetting("dynamicColor", "Colour by rate", true));

    public Tps() {
        super("tps", "TPS", "How fast the server is ticking, measured from its time updates.");
        setPosition(150, 115);
    }

    @Override
    protected String text(HudContext ctx) { return Fmt.fixed(TpsTracker.averageTps(), digits.get()) + " TPS"; }

    @Override
    protected String sample() { return Fmt.fixed(20.0, digits.get()) + " TPS"; }

    @Override
    protected int color(HudContext ctx) {
        if (!dynamicColor.get()) return textColor();
        return Fmt.lerpColor(RED, GREEN, (float) (TpsTracker.averageTps() / 20.0));
    }
}
