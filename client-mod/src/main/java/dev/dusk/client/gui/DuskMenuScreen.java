package dev.dusk.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * What the menu key opens, after Flex-HUD's: the launcher's brand cell
 * rising into place, and under it Preferences (gear), Modules and Edit layout (arrows),
 * drawn as the launcher's grey PxButtons.
 */
public class DuskMenuScreen extends MenuScreen {
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
        int alpha = Math.max(5, Math.round(255 * e));
        int bh = Math.max(Px.H, Math.min(32, rowY - 20));
        int ty = rowY - 12 - bh + Math.round((1 - e) * 16);
        Theme.brand(c, (this.width - Theme.brandWidth(c, bh)) / 2, ty, bh, alpha);

        boolean prefsHover = Vanilla.inside(mouseX, mouseY, prefsX(), rowY, 20, 20);
        boolean modulesHover = Vanilla.inside(mouseX, mouseY, modulesX(), rowY, 120, 20);
        boolean layoutHover = Vanilla.inside(mouseX, mouseY, layoutX(), rowY, 20, 20);
        boolean layoutOn = inWorld();

        Px.box(c, prefsX(), rowY, 20, 20, Theme.Family.GREY, prefsHover, false, alpha);
        Px.glyph(c, Icons.GEAR, prefsX(), rowY, 20, 20, Px.Tone.GREY, prefsHover, false, alpha);
        Px.box(c, modulesX(), rowY, 120, 20, Theme.Family.GREY, modulesHover, false, alpha);
        Px.label(c, "Modules", modulesX(), rowY, 120, 20, modulesHover, false, alpha);
        Px.box(c, layoutX(), rowY, 20, 20, Theme.Family.GREY, layoutHover && layoutOn, !layoutOn, alpha);
        Px.glyph(c, Icons.MOVE, layoutX(), rowY, 20, 20, Px.Tone.GREY, layoutHover && layoutOn, !layoutOn, alpha);

        if (prefsHover) Vanilla.tooltip(c, "Preferences", mouseX, mouseY, this.width, this.height);
        if (layoutHover) {
            Vanilla.tooltip(c, inWorld() ? "Edit HUD layout" : "Join a world to edit the HUD layout", mouseX, mouseY, this.width, this.height);
        }
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
