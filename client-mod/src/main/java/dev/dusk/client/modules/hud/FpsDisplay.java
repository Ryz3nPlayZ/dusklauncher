package dev.fasterlauncher.client.modules.hud;

import dev.fasterlauncher.client.module.Module;

/** FPS display HUD element. Value is sampled from the client's frame stats. */
public class FpsDisplay extends Module {
    private int fps;

    public FpsDisplay() {
        super("fps", "FPS Display", Category.HUD);
        setPosition(5, 100);
    }

    public int fps() { return fps; }
    public void setFps(int fps) { this.fps = fps; }
}
