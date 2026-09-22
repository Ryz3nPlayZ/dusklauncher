package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;

/** Time since the game was launched. */
public class Playtime extends TextHud {
    private static final long START = System.currentTimeMillis();

    public Playtime() {
        super("playtime", "Session Time", "Session", "How long this game session has been running.");
        setPosition(150, 49);
    }

    @Override
    protected String value(HudContext ctx) {
        return Fmt.duration((System.currentTimeMillis() - START) / 1000);
    }
}
