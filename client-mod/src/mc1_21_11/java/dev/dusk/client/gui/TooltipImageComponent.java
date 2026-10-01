package dev.dusk.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;

/** Draws a {@link TooltipImage} (food values, container grids) in an item tooltip. */
public record TooltipImageComponent(TooltipImage tooltip) implements ClientTooltipComponent {
    @Override
    public int getHeight(Font font) {
        return tooltip.height();
    }

    @Override
    public int getWidth(Font font) {
        return tooltip.width(font);
    }

    @Override
    public void renderImage(Font font, int x, int y, int width, int height, GuiGraphics graphics) {
        tooltip.draw(new GraphicsCanvas(graphics, font), x, y);
    }
}
