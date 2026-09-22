package dev.dusk.client.hud;

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

    public int textWidth(String s) { return mc.font.width(s); }

    public int lineHeight() { return mc.font.lineHeight; }
}
