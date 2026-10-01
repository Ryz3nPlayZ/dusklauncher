package dev.dusk.client.compat;

import net.minecraft.client.Minecraft;

/** F1's HUD-hidden flag, read and set (Options.hideGui until 26.2). */
public final class HudToggle {
    private HudToggle() {}

    public static boolean hidden(Minecraft mc) {
        return mc.options.hideGui;
    }

    public static void setHidden(Minecraft mc, boolean hidden) {
        mc.options.hideGui = hidden;
    }
}
