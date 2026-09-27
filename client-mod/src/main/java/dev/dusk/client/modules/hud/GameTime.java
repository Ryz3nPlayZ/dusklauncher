package dev.dusk.client.modules.hud;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;

import java.time.LocalTime;

/** Flex-HUD's In-Game Time: the world clock, where tick 0 is 06:00. */
public class GameTime extends TextHud {
    private final BoolSetting twentyFour = add(new BoolSetting("twentyFourHour", "24-hour format", Fmt.localeIs24Hour()));
    private final BoolSetting seconds = add(new BoolSetting("showSeconds", "Show seconds", false));

    public GameTime() {
        super("gametime", "In-Game Time", "Time of day in the world.");
        setPosition(150, 16);
    }

    private String format(long dayTime) {
        long timeOfDay = (dayTime % 24000 + 6000) % 24000;
        long total = Math.round(timeOfDay * 3.6);
        LocalTime t = LocalTime.of((int) (total / 3600 % 24), (int) (total / 60 % 60), (int) (total % 60));
        return t.format(Fmt.clock(twentyFour.get(), seconds.get()));
    }

    @Override
    protected String text(HudContext ctx) {
        var level = ctx.level();
        return level == null ? null : format(Compat.dayTime(level));
    }

    @Override
    protected String sample() {
        return format(12000);
    }
}
