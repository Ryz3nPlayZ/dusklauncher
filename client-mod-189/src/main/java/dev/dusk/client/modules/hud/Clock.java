package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.util.Fmt;

import java.util.Date;

public class Clock extends TextHud {
    private final BoolSetting twentyFour = add(new BoolSetting("twentyFourHour", "24-hour format", Fmt.localeIs24Hour()));
    private final BoolSetting seconds = add(new BoolSetting("showSeconds", "Show seconds", true));

    public Clock() {
        super("clock", "Clock", "Real-world local time.");
        setPosition(150, 5);
    }

    @Override
    protected String text(HudContext ctx) { return Fmt.clock(twentyFour.get(), seconds.get()).format(new Date()); }

    @Override
    protected String sample() { return text(null); }
}
