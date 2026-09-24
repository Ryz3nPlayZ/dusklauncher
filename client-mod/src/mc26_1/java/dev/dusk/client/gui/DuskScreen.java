package dev.dusk.client.gui;

import dev.dusk.client.DuskClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Screen base that hides the per-version draw entry points: subclasses
 * paint through {@link Canvas} in {@link #drawBackgroundOverlay} (after the
 * vanilla background, once per frame) and {@link #drawOverlay} (after the
 * widgets). 26.1 flavour: GuiGraphicsExtractor.
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
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (vanillaBackground()) super.extractBackground(graphics, mouseX, mouseY, delta);
        drawBackgroundOverlay(new GraphicsCanvas(graphics, this.font));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
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
        return k != null && k.matches(new KeyEvent(key, scancode, 0));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        return onClick(e.x(), e.y(), e.button()) || super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        return onDrag(e.x(), e.y(), e.button()) || super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        return onRelease(e.x(), e.y(), e.button()) || super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        return onScroll(mx, my, dy) || super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        return onKey(e.key(), e.scancode(), e.modifiers()) || super.keyPressed(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        return onChar((char) e.codepoint()) || super.charTyped(e);
    }
}
