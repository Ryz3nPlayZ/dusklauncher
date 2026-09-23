package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.hud.TpsTracker;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;

/** Flex-HUD's TPS readout: the server tick rate, red at 0 and green at 20. */
public class Tps extends TextHud {
    private static final int RED = 0xFFFF5555, GREEN = 0xFF55FF55;

    private final IntSetting digits = add(new IntSetting("digits", "Decimals", 1, 0, 4));
    private final BoolSetting dynamicColor = add(new BoolSetting("dynamicColor", "Colour by rate", true));

    public Tps() {
        super("tps", "TPS", "TPS", "How fast the server is ticking, measured from its time updates.");
        setPosition(150, 115);
        showLabel.set(false);
    }

    @Override
    protected String value(HudContext ctx) {
        return Fmt.fixed(TpsTracker.averageTps(), digits.get()) + " TPS";
    }

    @Override
    protected String sample() {
        return Fmt.fixed(20.0, digits.get()) + " TPS";
    }

    @Override
    protected int valueColor() {
        if (!dynamicColor.get()) return super.valueColor();
        return lerp((float) (TpsTracker.averageTps() / 20.0), RED, GREEN);
    }

    /** Straight per-channel blend, like ARGB.srgbLerp on the Flex-HUD side. */
    private static int lerp(float t, int from, int to) {
        t = Math.clamp(t, 0f, 1f);
        int a = 0xFF;
        int r = Math.round(((from >> 16) & 0xFF) + t * (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)));
        int g = Math.round(((from >> 8) & 0xFF) + t * (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)));
        int b = Math.round((from & 0xFF) + t * ((to & 0xFF) - (from & 0xFF)));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
