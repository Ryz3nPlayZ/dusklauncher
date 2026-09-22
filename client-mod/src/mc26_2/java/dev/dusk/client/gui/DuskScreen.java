package dev.dusk.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Screen base that hides the per-version draw entry points: subclasses
 * paint through {@link Canvas} in {@link #drawBackgroundOverlay} (after the
 * vanilla background, once per frame) and {@link #drawOverlay} (after the
 * widgets). 26.2 flavour: GuiGraphicsExtractor.
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
}
