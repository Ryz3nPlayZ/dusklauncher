package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;

/**
 * Draws the custom skies resource packs make for OptiFine ({@code optifine/sky}
 * and the older {@code mcpatcher/sky}) without OptiFine, Nuit or an interop
 * mod. On by default, as it is in OptiFine; it steps aside when Nuit is
 * installed so a sky isn't drawn twice.
 */
public class CustomSkies extends Module {
    private static CustomSkies instance;

    public CustomSkies() {
        super("customskies", "Custom Skies", Category.RENDER,
                "Shows resource packs' OptiFine custom skies.");
        instance = this;
        setEnabled(true);
    }

    public static boolean on() {
        CustomSkies m = instance;
        return m != null && m.enabled();
    }
}
