package dev.dusk.client.hud;

import dev.dusk.client.gui.Sans;
import dev.dusk.client.gui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Per-frame facts every HUD element wants: the game, the GUI-scaled
 * screen size and whether we are drawing inside the layout editor (where
 * elements with nothing to show draw sample data so they can be placed).
 */
public record HudContext(Minecraft mc, int width, int height, float partialTick, boolean editing) {
    public Font font() { return mc.font; }

    @Nullable
    public LocalPlayer player() { return mc.player; }

    @Nullable
    public ClientLevel level() { return mc.level; }

    /** {@code s}'s width in the HUD's font (the smooth sans when {@link HudRenderer#smooth}). */
    public int textWidth(String s) {
        if (!HudRenderer.smooth()) return mc.font.width(s);
        Theme.hudSansPush();
        try {
            return mc.font.width(Sans.wrap(s, 1f));
        } finally {
            Theme.hudSansPop();
        }
    }

    public int lineHeight() { return mc.font.lineHeight; }
}
