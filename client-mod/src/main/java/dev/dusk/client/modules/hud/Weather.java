package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;

public class Weather extends TextHud {
    public Weather() {
        super("weather", "Weather", "Weather", "Clear, rain or thunder.");
        setPosition(150, 38);
    }

    @Override
    protected String value(HudContext ctx) {
        var level = ctx.level();
        if (level == null) return null;
        if (level.isThundering()) return "Thunder";
        if (level.isRaining()) return "Rain";
        return "Clear";
    }
}
