package dev.dusk.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Screen base that hides the per-version draw entry points: subclasses
 * paint through {@link Canvas} in {@link #drawBackgroundOverlay} (after the
 * vanilla background, once per frame) and {@link #drawOverlay} (after the
 * widgets). 1.21.11 flavour: GuiGraphics.
 */
public abstract class DuskScreen extends Screen {
    protected DuskScreen(Component title) {
        super(title);
    }

    protected void drawBackgroundOverlay(Canvas canvas) {}

    protected void drawOverlay(Canvas canvas, int mouseX, int mouseY, float delta) {}

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.renderBackground(graphics, mouseX, mouseY, delta);
        drawBackgroundOverlay(new GraphicsCanvas(graphics, this.font));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        drawOverlay(new GraphicsCanvas(graphics, this.font), mouseX, mouseY, delta);
    }

    private record GraphicsCanvas(GuiGraphics g, Font font) implements Canvas {
        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            g.fill(x0, y0, x1, y1, argb);
        }

        @Override
        public void text(Component text, int x, int y, int argb) {
            g.drawString(font, text, x, y, argb);
        }

        @Override
        public void centeredText(Component text, int x, int y, int argb) {
            g.drawCenteredString(font, text, x, y, argb);
        }

        @Override
        public int textWidth(String text) {
            return font.width(text);
        }
    }
}
