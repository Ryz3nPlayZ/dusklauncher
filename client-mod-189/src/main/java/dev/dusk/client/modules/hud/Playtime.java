package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.util.Fmt;

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
        StringBuilder sb = new StringBuilder(10).append(ms / 3600000).append(':');
        String time = Fmt.pad2(Fmt.pad2(sb, ms / 60000 % 60).append(':'), ms / 1000 % 60).toString();
        return showPrefix.get() ? "Playtime: " + time : time;
    }

    @Override
    protected String sample() { return text(null); }
}
