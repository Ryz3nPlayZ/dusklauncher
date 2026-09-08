package dev.fasterlauncher.client.modules.hud;

import dev.fasterlauncher.client.module.Module;

/** Combo display HUD: shows current/last hit combo. Rendering pending. */
public class ComboDisplay extends Module {
    private int combo;

    public ComboDisplay() {
        super("combo", "Combo Display", Category.HUD);
        setPosition(5, 145);
    }

    public int combo() { return combo; }
    public void setCombo(int combo) { this.combo = combo; }
}
