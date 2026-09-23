package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;

/**
 * BactroMod's "Riptide Shield Position": while you spin with a riptide
 * trident the game gives every held item the trident's spin transform, which
 * throws an off-hand shield across the screen. This keeps the shield in its
 * normal place and leaves the trident spinning.
 */
public class RiptideShieldFix extends Module {
    private static RiptideShieldFix instance;

    public RiptideShieldFix() {
        super("riptideshieldfix", "Riptide Shield Fix", Category.RENDER,
                "Keeps an off-hand shield in place while riptiding.");
        instance = this;
        setEnabled(true);
    }

    public static boolean active() {
        return instance != null && instance.enabled();
    }
}
