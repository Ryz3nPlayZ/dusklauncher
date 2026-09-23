package dev.dusk.client.modules.misc;

import dev.dusk.client.module.Module;

/**
 * BactroMod's "F3+F4 Without Permission": the game hides the gamemode
 * switcher unless the server says you may use it. This opens it anyway --
 * the switch itself is still a server command, so a server that says no
 * still says no; you just get the menu.
 */
public class GameModeSwitcher extends Module {
    private static GameModeSwitcher instance;

    public GameModeSwitcher() {
        super("gamemodeswitcher", "Gamemode Switcher", Category.MISC,
                "Lets F3+F4 open the gamemode switcher without the server's permission flag.");
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.enabled();
    }
}
