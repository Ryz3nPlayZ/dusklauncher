package dev.dusk.client.compat;

import net.minecraft.client.resources.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * A styled text tree with the modern Component's API (literal, append,
 * withStyle, getString), so the menu code shared with 1.21.11 runs as is.
 * A child inherits its parent's style unless it sets its own. Drawn either
 * run by run in the sans ({@link #visit}) or as a section-sign string through
 * the vanilla font ({@link #legacy}).
 */
public final class Component {
    /** One stretch of text in one style; colour is 0xRRGGBB or -1 for the caller's colour. */
    public static final class Run {
        public final String text;
        public final int color;
        public final boolean bold, italic, underline, strikethrough;

        Run(String text, int color, boolean bold, boolean italic, boolean underline, boolean strikethrough) {
            this.text = text;
            this.color = color;
            this.bold = bold;
            this.italic = italic;
            this.underline = underline;
            this.strikethrough = strikethrough;
        }
    }

    public interface Visitor {
        void accept(Run run);
    }

    private final String text;
    private final List<Component> children = new ArrayList<Component>();
    private Integer color;
    private Boolean bold, italic, underline, strikethrough;

    private Component(String text) {
        this.text = text == null ? "" : text;
    }

    public static Component literal(String text) { return new Component(text); }

    public static Component empty() { return new Component(""); }

    public static Component translatable(String key, Object... args) { return new Component(I18n.format(key, args)); }

    public Component append(Component child) {
        children.add(child);
        return this;
    }

    public Component append(String child) { return append(literal(child)); }

    public Component withStyle(ChatFormatting... formats) {
        for (ChatFormatting f : formats) {
            switch (f) {
                case BOLD -> bold = true;
                case ITALIC -> italic = true;
                case UNDERLINE -> underline = true;
                case STRIKETHROUGH -> strikethrough = true;
                case OBFUSCATED -> { }
                case RESET -> { color = null; bold = italic = underline = strikethrough = null; }
                default -> color = f.getColor();
            }
        }
        return this;
    }

    /** {@code rgb} as 0xRRGGBB. */
    public Component withColor(int rgb) {
        color = rgb & 0xFFFFFF;
        return this;
    }

    public Component copy() {
        Component c = new Component(text);
        c.color = color;
        c.bold = bold;
        c.italic = italic;
        c.underline = underline;
        c.strikethrough = strikethrough;
        for (Component child : children) c.children.add(child.copy());
        return c;
    }

    public String getString() {
        StringBuilder sb = new StringBuilder();
        visit(r -> sb.append(r.text));
        return sb.toString();
    }

    /** Every non-empty run in order, with inherited styles resolved. */
    public void visit(Visitor visitor) {
        visit(visitor, -1, false, false, false, false);
    }

    private void visit(Visitor v, int pColor, boolean pBold, boolean pItalic, boolean pUnder, boolean pStrike) {
        int c = color != null ? color : pColor;
        boolean b = bold != null ? bold : pBold;
        boolean i = italic != null ? italic : pItalic;
        boolean u = underline != null ? underline : pUnder;
        boolean s = strikethrough != null ? strikethrough : pStrike;
        if (!text.isEmpty()) v.accept(new Run(text, c, b, i, u, s));
        for (Component child : children) child.visit(v, c, b, i, u, s);
    }

    /** The text with section-sign codes for the vanilla font; runs without a colour reset to the draw colour. */
    public String legacy() {
        StringBuilder sb = new StringBuilder();
        boolean[] styled = {false};
        visit(r -> {
            boolean plain = r.color < 0 && !r.bold && !r.italic && !r.underline && !r.strikethrough;
            if (styled[0] || !plain) sb.append("§r");
            if (r.color >= 0) sb.append(nearest(r.color));
            if (r.bold) sb.append("§l");
            if (r.italic) sb.append("§o");
            if (r.underline) sb.append("§n");
            if (r.strikethrough) sb.append("§m");
            styled[0] = !plain;
            sb.append(r.text);
        });
        return sb.toString();
    }

    private static String nearest(int rgb) {
        ChatFormatting best = ChatFormatting.WHITE;
        long bestD = Long.MAX_VALUE;
        for (ChatFormatting f : ChatFormatting.values()) {
            Integer fc = f.getColor();
            if (fc == null) continue;
            long dr = (fc >> 16 & 255) - (rgb >> 16 & 255), dg = (fc >> 8 & 255) - (rgb >> 8 & 255), db = (fc & 255) - (rgb & 255);
            long d = dr * dr + dg * dg + db * db;
            if (d < bestD) { bestD = d; best = f; }
        }
        return best.toString();
    }

    @Override
    public String toString() { return getString(); }
}
