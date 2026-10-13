package dev.dusk.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * The modern look's type: Plus Jakarta Sans in place of the default font
 * while {@link Theme#sansOn} holds. The font is rasterised for the scale it
 * is drawn at ({@code font/ui_<q>.json}, oversample q/4 window pixels per
 * GUI pixel), so it stays sharp at every GUI scale and inside our own
 * transforms; bold runs take the bold cut instead of vanilla's smear.
 */
public final class Sans {
    private Sans() {}

    private static final int MIN_Q = 2, MAX_Q = 48;
    private static final FontDescription[][] FONTS = new FontDescription[2][MAX_Q + 1];

    public static boolean supported() {
        return true;
    }

    private static FontDescription font(boolean bold, int q) {
        FontDescription f = FONTS[bold ? 1 : 0][q];
        if (f == null) {
            f = new FontDescription.Resource(ResourceLocation.fromNamespaceAndPath("duskclient", (bold ? "ui_bold_" : "ui_") + q));
            FONTS[bold ? 1 : 0][q] = f;
        }
        return f;
    }

    /** The quarter-pixel step for text drawn {@code scale} GUI pixels to the unit by the current transform. */
    private static int step(float scale) {
        double e = scale * Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        return Math.max(MIN_Q, Math.min(MAX_Q, (int) Math.round(e * 4)));
    }

    /** {@code text} in the sans cut for {@code scale}, or unchanged when sans is off. */
    public static Component wrap(Component text, float scale) {
        if (!Theme.sansOn()) return text;
        int q = step(scale);
        MutableComponent out = Component.empty();
        text.visit((style, s) -> {
            if (s.isEmpty()) return Optional.empty();
            Style st = style;
            if (FontDescription.DEFAULT.equals(st.getFont())) st = st.withBold(false).withFont(font(st.isBold(), q));
            out.append(Component.literal(s).withStyle(st));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    public static Component wrap(String text, float scale) {
        return wrap(Component.literal(text), scale);
    }
}
