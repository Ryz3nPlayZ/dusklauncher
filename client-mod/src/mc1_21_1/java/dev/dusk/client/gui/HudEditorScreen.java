package dev.dusk.client.gui;

import dev.dusk.client.DuskClient;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.hud.HudRenderer;
import dev.dusk.client.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * In-game HUD layout editor. The world keeps rendering under a light
 * overlay; every enabled HUD element is drawn live and can be dragged,
 * scaled with the wheel, or right-clicked for its settings. The module
 * list and settings live in one centered {@link ModuleWindow} rather than
 * a full-screen menu.
 */
public class HudEditorScreen extends DuskScreen {
    private static final int NUDGE_MODS = GLFW.GLFW_MOD_SHIFT;

    @Nullable
    private final Screen parent;
    private final ModuleWindow window;
    private HudElement hovered;
    private HudElement selected;
    private HudElement dragging;
    private int dragOffX, dragOffY;

    public HudEditorScreen(@Nullable Screen parent) {
        super(Component.literal("Dusk HUD Editor"));
        this.parent = parent;
        this.window = new ModuleWindow(Minecraft.getInstance().font, this::save,
                m -> { if (m instanceof HudElement e) selected = e; }, this::onClose);
    }

    private void save() {
        if (DuskClient.modules() != null) DuskClient.modules().saveConfig();
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
    protected void drawOverlay(Canvas c, int mouseX, int mouseY, float delta) {
        HudContext ctx = context(delta);
        window.layout(this.width, this.height);
        boolean overWindow = window.contains(mouseX, mouseY);
        hovered = overWindow ? null : elementAt(mouseX, mouseY, ctx);

        for (HudElement e : elements()) {
            if (!e.enabled()) continue;
            HudRenderer.draw(c, e, ctx);
            int w = e.screenWidth(ctx), h = e.screenHeight(ctx);
            int color = (e == selected || e == dragging) ? Theme.OUTLINE_HOT
                    : e == hovered ? Theme.OUTLINE : 0x40FFFFFF;
            c.outline(e.x() - 1, e.y() - 1, w + 2, h + 2, color);
            if (e == hovered || e == dragging) {
                String tag = e.name() + " " + e.scalePercent() + "%";
                int tx = Math.min(e.x(), this.width - c.textWidth(tag) - 2);
                int tyy = e.y() + h + 3 > this.height - 10 ? e.y() - 11 : e.y() + h + 3;
                c.text(tag, tx, tyy, Theme.ACCENT, true);
            }
        }

        String hint = "Drag to move  |  Scroll to scale  |  Right-click for settings  |  Esc to close";
        c.centeredText(hint, this.width / 2, this.height - (window.minimized() ? 34 : 12), Theme.TEXT_MUTED, true);

        window.render(c, mouseX, mouseY);
    }

    // ---- input --------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (window.contains(mx, my)) {
            window.click(mx, my, button);
            return true;
        }
        window.blur();
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
            window.open(hit);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging != null) {
            dragging.setPosition((int) mx - dragOffX, (int) my - dragOffY);
            HudRenderer.clampToScreen(dragging, context(0));
            return true;
        }
        window.drag(mx, my);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging != null) {
            dragging = null;
            save();
        }
        window.release();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (window.scroll(mx, my, dy)) return true;
        HudElement target = elementAt(mx, my, context(0));
        if (target != null && dy != 0) {
            target.setScalePercent(target.scalePercent() + (dy > 0 ? 5 : -5));
            HudRenderer.clampToScreen(target, context(0));
            save();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (window.keyPressed(key, modifiers)) return true;
        if (DuskClient.settingsKey() != null && DuskClient.settingsKey().matches(key, scancode)) {
            onClose();
            return true;
        }
        if (selected != null) {
            int step = (modifiers & NUDGE_MODS) != 0 ? 10 : 1;
            int nx = selected.x(), ny = selected.y();
            switch (key) {
                case GLFW.GLFW_KEY_LEFT -> nx -= step;
                case GLFW.GLFW_KEY_RIGHT -> nx += step;
                case GLFW.GLFW_KEY_UP -> ny -= step;
                case GLFW.GLFW_KEY_DOWN -> ny += step;
                default -> { return super.keyPressed(key, scancode, modifiers); }
            }
            selected.setPosition(nx, ny);
            HudRenderer.clampToScreen(selected, context(0));
            save();
            return true;
        }
        return super.keyPressed(key, scancode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return window.charTyped(chr);
    }

    /** Opens straight into a module's settings (used by the title-screen menu). */
    public HudEditorScreen focus(Module module) {
        window.open(module);
        return this;
    }

    @Override
    public void onClose() {
        window.blur();
        save();
        if (this.minecraft != null) Compat.setScreen(this.minecraft, parent);
    }
}
