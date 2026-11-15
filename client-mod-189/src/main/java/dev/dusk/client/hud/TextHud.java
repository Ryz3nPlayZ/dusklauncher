package dev.dusk.client.hud;

import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;

/** A one-line HUD element; {@link #text} returns null when there is nothing to show. */
public abstract class TextHud extends HudElement {
    private static final long LAUNCH = System.currentTimeMillis();

    protected final BoolSetting shadow;
    protected final BoolSetting chroma;
    protected final ColorSetting color;

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

    protected abstract String text(HudContext ctx);

    protected String sample() { return "-"; }

    protected int textColor() {
        return chroma.get() ? chromaColor() : 0xFF000000 | color.argb();
    }

    protected static int chromaColor() {
        float hue = (System.currentTimeMillis() - LAUNCH) % 4000 / 4000f;
        return 0xFF000000 | java.awt.Color.HSBtoRGB(hue, 1f, 1f);
    }

    protected int color(HudContext ctx) { return textColor(); }

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
    public boolean visible(HudContext ctx) { return ctx.player() != null && live(ctx) != null; }

    @Override
    public int width(HudContext ctx) {
        String v = current(ctx);
        return v == null ? 0 : ctx.textWidth(v);
    }

    @Override
    public int height(HudContext ctx) { return ctx.lineHeight(); }

    @Override
    public void render(Canvas c, HudContext ctx) {
        String v = current(ctx);
        if (v != null) c.text(v, 0, 0, color(ctx), shadow.get());
    }
}
