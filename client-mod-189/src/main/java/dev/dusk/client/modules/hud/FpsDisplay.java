package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import net.minecraft.client.Minecraft;

public class FpsDisplay extends TextHud {
    public FpsDisplay() {
        super("fps", "FPS", "Frames per second.");
        setPosition(5, 5);
        setEnabled(true);
    }

    @Override
    protected String text(HudContext ctx) { return Minecraft.getDebugFPS() + " FPS"; }

    @Override
    protected String sample() { return "100 FPS"; }
}
