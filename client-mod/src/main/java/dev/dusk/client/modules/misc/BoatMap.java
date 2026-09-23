package dev.dusk.client.modules.misc;

import dev.dusk.client.module.Module;

/**
 * BactroMod's "Show Map in Boat": rowing counts as busy hands, so the game
 * drops whatever you are holding out of view. This keeps a filled map up
 * while you row, and leaves every other item behaving normally.
 */
public class BoatMap extends Module {
    private static BoatMap instance;

    public BoatMap() {
        super("boatmap", "Boat Map", Category.MISC,
                "Keeps a filled map visible while you are rowing a boat.");
        instance = this;
        setEnabled(true);
    }

    public static boolean active() {
        return instance != null && instance.enabled();
    }
}
