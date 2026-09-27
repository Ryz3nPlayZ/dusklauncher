package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;

import java.util.Locale;

/** Flex-HUD's Playtime: time since the game was launched, "Playtime: 1:02:03". */
public class Playtime extends TextHud {
    private static final long START = System.currentTimeMillis();

    private final BoolSetting showPrefix = add(new BoolSetting("showPrefix", "Show prefix", true));

    public Playtime() {
        super("playtime", "Playtime", "How long the game has been running.");
        setPosition(150, 49);
    }

    @Override
    protected String text(HudContext ctx) {
        long ms = System.currentTimeMillis() - START;
        String time = String.format(Locale.ROOT, "%1d:%02d:%02d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60);
        return showPrefix.get() ? "Playtime: " + time : time;
    }

    @Override
    protected String sample() {
        return text(null);
    }
}
