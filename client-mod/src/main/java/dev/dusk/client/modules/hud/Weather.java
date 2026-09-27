package dev.dusk.client.modules.hud;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;

/** Flex-HUD's Weather Display: a 16px clear / rain / thunder icon, day or night, where there is a sky. */
public class Weather extends HudElement {
    private static final int SIZE = 16;

    public Weather() {
        super("weather", "Weather", "Clear, rain or thunder, as an icon.");
        setPosition(150, 38);
    }

    @Override
    public boolean visible(HudContext ctx) {
        var level = ctx.level();
        return ctx.player() != null && level != null
                && level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
    }

    @Override
    public int width(HudContext ctx) {
        return SIZE;
    }

    @Override
    public int height(HudContext ctx) {
        return SIZE;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        c.blit("duskclient:textures/hud/weather/" + icon(ctx) + ".png", 0, 0, 0, 0, SIZE, SIZE, SIZE, SIZE);
    }

    private static String icon(HudContext ctx) {
        var level = ctx.level();
        if (level == null) return "clear_day";
        long t = Math.floorMod(Compat.dayTime(level), 24000L);
        String time = t >= 12750 && t < 23250 ? "night" : "day";
        if (level.isThundering()) return "thunder_" + time;
        if (level.isRaining()) return "rain_" + time;
        return "clear_" + time;
    }
}
