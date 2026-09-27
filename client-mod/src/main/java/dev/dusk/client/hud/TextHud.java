package dev.dusk.client.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;

/**
 * Base for the one-line text elements, drawn the way Flex-HUD's text modules
 * are: one string in one colour (white by default, or chroma) at the origin.
 * Subclasses supply {@link #text} (null = nothing to show) and a
 * {@link #sample} the editor draws in its place.
 */
public abstract class TextHud extends HudElement {
    /** Launch time, the zero for chroma's hue cycle. */
    private static final long LAUNCH = System.currentTimeMillis();

    protected final BoolSetting shadow;
    protected final BoolSetting chroma;
    protected final ColorSetting color;

    /** {@link #text} for the frame {@link #cachedFor} was built for: a frame asks for it up to four times. */
    private HudContext cachedFor;
    private String cached;

    protected TextHud(String id, String name, String description) {
        this(id, name, description, 0xFFFFFFFF);
    }

    protected TextHud(String id, String name, String description, int defaultColor) {
        this(id, name, description, defaultColor, false);
    }

    protected TextHud(String id, String name, String description, int defaultColor, boolean backgroundByDefault) {
        super(id, name, description, backgroundByDefault);
        shadow = add(new BoolSetting("shadow", "Text shadow", true));
        chroma = add(new BoolSetting("chroma", "Chroma", false));
        color = add(new ColorSetting("color", "Text colour", defaultColor));
    }

    /** Live text, or null when there is nothing to display right now. */
    protected abstract String text(HudContext ctx);

    /** What the editor shows when {@link #text} is null. */
    protected String sample() {
        return "-";
    }

    /** The configured text colour: the colour setting, or the chroma cycle. */
    protected int textColor() {
        return chroma.get() ? chromaColor() : 0xFF000000 | color.argb();
    }

    /** The chroma cycle's colour right now. */
    protected static int chromaColor() {
        float hue = (System.currentTimeMillis() - LAUNCH) % 4000 / 4000f;
        return 0xFF000000 | java.awt.Color.HSBtoRGB(hue, 1f, 1f);
    }

    /** Colour for this frame's text; overridden by elements that colour themselves. */
    protected int color(HudContext ctx) {
        return textColor();
    }

    private String live(HudContext ctx) {
        if (cachedFor != ctx) {
            cached = text(ctx);
            cachedFor = ctx;
        }
        return cached;
    }

    protected final String current(HudContext ctx) {
        String v = live(ctx);
        if (v == null && ctx.editing()) v = sample();
        return v;
    }

    @Override
    public boolean visible(HudContext ctx) {
        return ctx.player() != null && live(ctx) != null;
    }

    @Override
    public int width(HudContext ctx) {
        String v = current(ctx);
        return v == null ? 0 : ctx.textWidth(v);
    }

    @Override
    public int height(HudContext ctx) {
        return ctx.lineHeight();
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        String v = current(ctx);
        if (v != null) c.text(v, 0, 0, color(ctx), shadow.get());
    }
}
