package dev.dusk.client.modules.hud;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;

import java.util.Locale;

/** In-game clock: tick 0 is 06:00. */
public class GameTime extends TextHud {
    public GameTime() {
        super("gametime", "In-game Time", "World", "Time of day in the world.");
        setPosition(150, 16);
    }

    @Override
    protected String value(HudContext ctx) {
        var level = ctx.level();
        if (level == null) return null;
        long t = Compat.dayTime(level) % 24000;
        int hours = (int) ((t / 1000 + 6) % 24);
        int minutes = (int) ((t % 1000) * 60 / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
    }
}
