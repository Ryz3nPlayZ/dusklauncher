package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;


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
        // always with the hours, "0:05:12"
        StringBuilder sb = new StringBuilder(10).append(ms / 3_600_000).append(':');
        String time = Fmt.pad2(Fmt.pad2(sb, ms / 60_000 % 60).append(':'), ms / 1000 % 60).toString();
        return showPrefix.get() ? "Playtime: " + time : time;
    }

    @Override
    protected String sample() {
        return text(null);
    }
}
