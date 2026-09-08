package dev.fasterlauncher.client.modules.hud;

import dev.fasterlauncher.client.module.Module;

/**
 * Keystrokes HUD (WASD + mouse buttons). Actual rendering is done by the HUD
 * renderer once the DrawContext-based HUD layer is implemented; this class
 * holds state and layout.
 */
public class Keystrokes extends Module {
    private boolean showMouseButtons = true;
    private boolean showSpaceBar = true;

    public Keystrokes() {
        super("keystrokes", "Keystrokes", Category.HUD);
        setPosition(5, 5);
    }
}
