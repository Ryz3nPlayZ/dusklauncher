package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;

/** Flex-HUD's Memory Usage: "Mem: 42%" of the maximum heap. */
public class Memory extends TextHud {
    public Memory() {
        super("memory", "Memory Usage", "How much of the game's memory is in use.");
        setPosition(150, 71);
    }

    @Override
    protected String text(HudContext ctx) {
        Runtime rt = Runtime.getRuntime();
        double percent = (double) (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory() * 100;
        return "Mem: " + (int) percent + "%";
    }

    @Override
    protected String sample() {
        return text(null);
    }
}
