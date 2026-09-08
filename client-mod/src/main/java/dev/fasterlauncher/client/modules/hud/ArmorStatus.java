package dev.fasterlauncher.client.modules.hud;

import dev.fasterlauncher.client.module.Module;

/** Armor Status HUD: helmet/chest/legs/boots durability. Rendering pending. */
public class ArmorStatus extends Module {
    public ArmorStatus() {
        super("armorstatus", "Armor Status", Category.HUD);
        setPosition(5, 125);
    }
}
