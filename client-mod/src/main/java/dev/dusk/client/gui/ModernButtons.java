package dev.dusk.client.gui;

import dev.dusk.client.compat.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.resources.language.I18n;

/**
 * The pause menu's vanilla buttons (and the ones Dusk adds to it) drawn as
 * the modern look's {@link Glass} pills, like the title screen's. Icon
 * buttons and every other vanilla screen keep vanilla's look.
 */
public final class ModernButtons {
    private ModernButtons() {}

    /** Whether {@code button}, about to draw, takes the modern look. */
    public static boolean applies(Object button) {
        if (!(button instanceof Button) || button instanceof SpriteIconButton) return false;
        if (!(Compat.currentScreen(Minecraft.getInstance()) instanceof PauseScreen)) return false;
        Theme.sync();
        return Theme.modern();
    }

    public static void draw(Canvas c, int x, int y, int w, int h, String text, boolean hot, boolean active) {
        // Back to Game is the menu's Play: the one white pill
        boolean primary = text.equals(I18n.get("menu.returnToGame"));
        Glass.bar(c, x, y, w, h, hot, !active, primary);
        Glass.label(c, text, x, y, w, h, Glass.text(hot, !active, primary));
    }
}
