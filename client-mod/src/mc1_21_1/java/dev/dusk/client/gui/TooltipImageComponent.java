package dev.dusk.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;

/** Draws a {@link TooltipImage} (food values, container grids) in an item tooltip. 1.21.1 flavour. */
public record TooltipImageComponent(TooltipImage tooltip) implements ClientTooltipComponent {
    @Override
    public int getHeight() {
        return tooltip.height();
    }

    @Override
    public int getWidth(Font font) {
        return tooltip.width(font);
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
        tooltip.draw(new GraphicsCanvas(graphics, font), x, y);
    }
}
