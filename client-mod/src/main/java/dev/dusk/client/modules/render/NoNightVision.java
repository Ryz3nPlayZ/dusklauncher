package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;

/**
 * BactroMod's "Night Vision" toggle: suppresses the brightness boost the
 * Night Vision effect applies, including the flicker as it runs out. The
 * effect's other behaviour (underwater visibility) is untouched.
 */
public class NoNightVision extends Module {
    private static NoNightVision instance;

    public NoNightVision() {
        super("nonightvision", "No Night Vision", Category.RENDER,
                "Stops the Night Vision effect from brightening the world.");
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.enabled();
    }
}
