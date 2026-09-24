package dev.dusk.client.gui;

import dev.dusk.client.DuskClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Screen base that hides the per-version draw entry points: subclasses
 * paint through {@link Canvas} in {@link #drawBackgroundOverlay} (after the
 * vanilla background, once per frame) and {@link #drawOverlay} (after the
 * widgets). 1.21.6–1.21.8 flavour: GuiGraphics.
 */
public abstract class DuskScreen extends Screen {
    protected DuskScreen(Component title) {
        super(title);
    }

    protected void drawBackgroundOverlay(Canvas canvas) {}

    protected void drawOverlay(Canvas canvas, int mouseX, int mouseY, float delta) {}

    /** False skips vanilla's blurred/dirt background (the HUD editor wants the world visible). */
    protected boolean vanillaBackground() {
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (vanillaBackground()) super.renderBackground(graphics, mouseX, mouseY, delta);
        drawBackgroundOverlay(new GraphicsCanvas(graphics, this.font));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        drawOverlay(new GraphicsCanvas(graphics, this.font), mouseX, mouseY, delta);
    }

    // ---- version-neutral input: screens override these, never the vanilla signatures ----

    protected boolean onClick(double mx, double my, int button) { return false; }

    protected boolean onDrag(double mx, double my, int button) { return false; }

    protected boolean onRelease(double mx, double my, int button) { return false; }

    protected boolean onScroll(double mx, double my, double amount) { return false; }

    protected boolean onKey(int key, int scancode, int modifiers) { return false; }

    protected boolean onChar(char ch) { return false; }

    /** True when the key is bound to "Open Dusk Menu". */
    protected static boolean isSettingsKey(int key, int scancode) {
        KeyMapping k = DuskClient.settingsKey();
        return k != null && k.matches(key, scancode);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        return onClick(mx, my, button) || super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return onDrag(mx, my, button) || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return onRelease(mx, my, button) || super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        return onScroll(mx, my, dy) || super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        return onKey(key, scancode, modifiers) || super.keyPressed(key, scancode, modifiers);
    }

    @Override
    public boolean charTyped(char ch, int modifiers) {
        return onChar(ch) || super.charTyped(ch, modifiers);
    }
}
