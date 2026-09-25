package dev.dusk.client.gui;

import dev.dusk.client.DuskClient;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.hud.HudRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * In-game HUD layout editor. The world keeps rendering under a light
 * overlay; every enabled HUD element is drawn live and can be dragged,
 * scaled with the wheel, or right-clicked for its settings.
 */
public class HudEditorScreen extends MenuScreen {
    private static final int NUDGE_MODS = GLFW.GLFW_MOD_SHIFT;

    private HudElement hovered;
    private HudElement selected;
    private HudElement dragging;
    private int dragOffX, dragOffY;

    public HudEditorScreen(@Nullable Screen parent) {
        super(Component.literal("Dusk HUD Editor"), parent);
    }

    private void save() {
        saveModules();
    }

    private HudContext context(float delta) {
        return new HudContext(Minecraft.getInstance(), this.width, this.height, delta, true);
    }

    private List<HudElement> elements() {
        return DuskClient.modules() == null ? List.of() : DuskClient.modules().hudElements();
    }

    @Nullable
    private HudElement elementAt(double mx, double my, HudContext ctx) {
        List<HudElement> all = elements();
        for (int i = all.size() - 1; i >= 0; i--) { // topmost (last drawn) wins
            HudElement e = all.get(i);
            if (!e.enabled()) continue;
            int w = e.screenWidth(ctx), h = e.screenHeight(ctx);
            if (mx >= e.x() && my >= e.y() && mx < e.x() + w && my < e.y() + h) return e;
        }
        return null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected boolean vanillaBackground() {
        return false;
    }

    @Override
    protected void drawBackgroundOverlay(Canvas canvas) {
        canvas.fill(0, 0, this.width, this.height, Theme.OVERLAY);
    }

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        HudContext ctx = context(delta);
        hovered = elementAt(mouseX, mouseY, ctx);

        for (HudElement e : elements()) {
            if (!e.enabled()) continue;
            HudRenderer.draw(c, e, ctx);
            int w = e.screenWidth(ctx), h = e.screenHeight(ctx);
            int color = (e == selected || e == dragging) ? 0xFFFFFFFF : e == hovered ? 0x80FFFFFF : 0x40FFFFFF;
            c.outline(e.x() - 1, e.y() - 1, w + 2, h + 2, color);
            if (e == hovered || e == dragging) {
                String tag = e.name() + " " + e.scalePercent() + "%";
                int tx = Math.min(e.x(), this.width - c.textWidth(tag) - 2);
                int tyy = e.y() + h + 3 > this.height - 10 ? e.y() - 11 : e.y() + h + 3;
                c.text(tag, tx, tyy, Vanilla.TEXT, true);
            }
        }

        String hint = "Drag to move  |  Scroll to scale  |  Right-click for settings  |  Esc to go back";
        c.centeredText(hint, this.width / 2, this.height - 12, Vanilla.TEXT_DIM, true);
    }

    // ---- input --------------------------------------------------------

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        HudElement hit = elementAt(mx, my, context(0));
        if (hit == null) {
            selected = null;
            return true;
        }
        selected = hit;
        if (button == 0) {
            dragging = hit;
            dragOffX = (int) mx - hit.x();
            dragOffY = (int) my - hit.y();
        } else if (button == 1) {
            open(ConfigScreen.module(this, hit));
        }
        return true;
    }

    @Override
    protected boolean menuDrag(double mx, double my, int button) {
        if (dragging == null) return false;
        dragging.setPosition((int) mx - dragOffX, (int) my - dragOffY);
        HudRenderer.clampToScreen(dragging, context(0));
        return true;
    }

    @Override
    protected boolean menuRelease(double mx, double my, int button) {
        if (dragging == null) return false;
        dragging = null;
        save();
        return true;
    }

    @Override
    protected boolean menuScroll(double mx, double my, double amount) {
        HudElement target = elementAt(mx, my, context(0));
        if (target != null && amount != 0) {
            target.setScalePercent(target.scalePercent() + (amount > 0 ? 5 : -5));
            HudRenderer.clampToScreen(target, context(0));
            save();
            return true;
        }
        return false;
    }

    @Override
    protected boolean menuKey(int key, int scancode, int modifiers) {
        if (selected == null) return false;
        int step = (modifiers & NUDGE_MODS) != 0 ? 10 : 1;
        int nx = selected.x(), ny = selected.y();
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> nx -= step;
            case GLFW.GLFW_KEY_RIGHT -> nx += step;
            case GLFW.GLFW_KEY_UP -> ny -= step;
            case GLFW.GLFW_KEY_DOWN -> ny += step;
            default -> { return false; }
        }
        selected.setPosition(nx, ny);
        HudRenderer.clampToScreen(selected, context(0));
        save();
        return true;
    }
}
