package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class Clock extends TextHud {
    private static final DateTimeFormatter H24 = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter H24S = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter H12 = DateTimeFormatter.ofPattern("h:mm a");
    private static final DateTimeFormatter H12S = DateTimeFormatter.ofPattern("h:mm:ss a");

    private final BoolSetting twelveHour = add(new BoolSetting("twelveHour", "12-hour clock", false));
    private final BoolSetting seconds = add(new BoolSetting("seconds", "Show seconds", false));

    public Clock() {
        super("clock", "Clock", "Time", "Real-world local time.");
        setPosition(150, 5);
    }

    @Override
    protected String value(HudContext ctx) {
        DateTimeFormatter f = twelveHour.get() ? (seconds.get() ? H12S : H12) : (seconds.get() ? H24S : H24);
        return LocalTime.now().format(f);
    }
}
