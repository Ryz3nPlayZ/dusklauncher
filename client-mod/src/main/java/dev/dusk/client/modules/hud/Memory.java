package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;

public class Memory extends TextHud {
    private final BoolSetting percent = add(new BoolSetting("percent", "Show percent", true));

    public Memory() {
        super("memory", "Memory", "Mem", "JVM heap in use.");
        setPosition(150, 71);
    }

    @Override
    protected String value(HudContext ctx) {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long used = rt.totalMemory() - rt.freeMemory();
        String s = (used >> 20) + " / " + (max >> 20) + " MB";
        return percent.get() ? s + " (" + (used * 100 / Math.max(1, max)) + "%)" : s;
    }
}
