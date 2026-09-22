package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;

public class FpsDisplay extends TextHud {
    public FpsDisplay() {
        super("fps", "FPS", "FPS", "Frames per second.");
        setPosition(5, 5);
        setEnabled(true);
    }

    @Override
    protected String value(HudContext ctx) {
        return Integer.toString(ctx.mc().getFps());
    }
}
