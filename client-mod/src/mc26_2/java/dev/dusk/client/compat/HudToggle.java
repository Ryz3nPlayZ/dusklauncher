package dev.dusk.client.compat;

import net.minecraft.client.Minecraft;

/** F1's HUD-hidden flag, read and set (Hud#toggle since 26.2). */
public final class HudToggle {
    private HudToggle() {}

    public static boolean hidden(Minecraft mc) {
        return mc.gui.hud.isHidden();
    }

    public static void setHidden(Minecraft mc, boolean hidden) {
        if (mc.gui.hud.isHidden() != hidden) mc.gui.hud.toggle();
    }
}
