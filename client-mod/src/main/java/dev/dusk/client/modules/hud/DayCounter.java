package dev.dusk.client.modules.hud;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;

/** Flex-HUD's Day Counter: "Day 12". */
public class DayCounter extends TextHud {
    public DayCounter() {
        super("day", "Day Counter", "How many in-game days the world has seen.");
        setPosition(150, 27);
    }

    @Override
    protected String text(HudContext ctx) {
        var level = ctx.level();
        return level == null ? null : "Day " + Compat.dayTime(level) / 24000;
    }

    @Override
    protected String sample() {
        return "Day 5";
    }
}
