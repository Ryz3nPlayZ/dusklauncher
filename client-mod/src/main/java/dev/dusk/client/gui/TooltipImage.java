package dev.dusk.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;

import java.util.List;

/**
 * Something drawn inside an item tooltip instead of a line of text, like
 * Hunger Info's food values or Container Preview's grid. Goes into the
 * tooltip's lines as a {@link Line}, which the per-version tooltip mixin
 * turns into a TooltipImageComponent.
 */
public interface TooltipImage {
    int height();

    int width(Font font);

    void draw(Canvas c, int x, int y);

    /** Stands in a tooltip's line list for a {@link TooltipImage}. */
    final class Line implements Component, FormattedCharSequence {
        private static final ComponentContents EMPTY = Component.empty().getContents();

        private final TooltipImage image;

        public Line(TooltipImage image) {
            this.image = image;
        }

        public TooltipImage image() {
            return image;
        }

        @Override
        public Style getStyle() {
            return Style.EMPTY;
        }

        @Override
        public ComponentContents getContents() {
            return EMPTY;
        }

        @Override
        public List<Component> getSiblings() {
            return List.of();
        }

        @Override
        public FormattedCharSequence getVisualOrderText() {
            return this;
        }

        @Override
        public boolean accept(FormattedCharSink sink) {
            return true;
        }
    }
}
