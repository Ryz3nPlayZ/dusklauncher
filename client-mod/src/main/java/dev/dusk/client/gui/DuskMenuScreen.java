package dev.dusk.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * What the menu key opens, after Flex-HUD's: the wordmark rising into
 * place, and under it Preferences (gear), Modules and Edit layout (arrows).
 */
public class DuskMenuScreen extends MenuScreen {
    private static final String WORDMARK = "DUSK";
    private static final long INTRO_MS = 500;

    private final long openedAt = System.currentTimeMillis();

    public DuskMenuScreen(@Nullable Screen parent) {
        super(Component.literal("Dusk"), parent);
    }

    private int rowY() { return this.height / 2 - 10; }
    private int prefsX() { return this.width / 2 - 90; }
    private int modulesX() { return this.width / 2 - 60; }
    private int layoutX() { return this.width / 2 + 70; }

    /** 0..1 through the intro, eased out. */
    private float intro() {
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / (float) INTRO_MS);
        return 1 - (1 - t) * (1 - t);
    }

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        float e = intro();
        int rowY = rowY();
        int scale = Math.max(2, Math.min(5, (rowY - 30) / 9));
        int tw = Theme.wordmarkWidth(c, WORDMARK, scale);
        int ty = rowY - 20 - 9 * scale + Math.round((1 - e) * 16);
        Theme.wordmark(c, WORDMARK, (this.width - tw) / 2, ty, scale, e);

        int tint = Math.max(5, Math.round(255 * e)) << 24 | 0xFFFFFF;
        boolean prefsHover = Vanilla.inside(mouseX, mouseY, prefsX(), rowY, 20, 20);
        boolean modulesHover = Vanilla.inside(mouseX, mouseY, modulesX(), rowY, 120, 20);
        boolean layoutHover = Vanilla.inside(mouseX, mouseY, layoutX(), rowY, 20, 20);
        int alpha = tint >>> 24;

        Vanilla.button(c, prefsX(), rowY, 20, 20, prefsHover, true, tint);
        glyph(c, Icons.GEAR, prefsX(), rowY, true, alpha);
        Vanilla.button(c, modulesX(), rowY, 120, 20, modulesHover, true, tint);
        Vanilla.buttonLabel(c, "Modules", modulesX(), rowY, 120, 20, true, alpha);
        Vanilla.button(c, layoutX(), rowY, 20, 20, layoutHover, inWorld(), tint);
        glyph(c, Icons.MOVE, layoutX(), rowY, inWorld(), alpha);

        if (prefsHover) Vanilla.tooltip(c, "Preferences", mouseX, mouseY, this.width, this.height);
        if (layoutHover) {
            Vanilla.tooltip(c, inWorld() ? "Edit HUD layout" : "Join a world to edit the HUD layout", mouseX, mouseY, this.width, this.height);
        }
    }

    /** A 7x7 glyph at 2x, centred on a 20x20 button, with vanilla's text shadow. */
    private static void glyph(Canvas c, Icons icon, int x, int y, boolean active, int alpha) {
        int a = Math.max(5, alpha) << 24;
        icon.draw(c, x + 4, y + 4, a | 0x3F3F3F, 2);
        icon.draw(c, x + 3, y + 3, a | ((active ? Vanilla.TEXT : Vanilla.TEXT_OFF) & 0xFFFFFF), 2);
    }

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        if (button != 0) return false;
        int rowY = rowY();
        if (Vanilla.inside(mx, my, prefsX(), rowY, 20, 20)) {
            open(ConfigScreen.preferences(this));
        } else if (Vanilla.inside(mx, my, modulesX(), rowY, 120, 20)) {
            open(new DuskSettingsScreen(this));
        } else if (Vanilla.inside(mx, my, layoutX(), rowY, 20, 20) && inWorld()) {
            open(new HudEditorScreen(this));
        } else {
            return false;
        }
        return true;
    }
}
