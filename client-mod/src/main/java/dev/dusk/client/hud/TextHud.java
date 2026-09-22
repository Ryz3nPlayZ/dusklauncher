package dev.dusk.client.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;

/**
 * Base for the many one-line "Label: value" elements. Subclasses supply
 * {@link #value} (null = nothing to show) and a {@link #sample} the editor
 * draws in its place.
 */
public abstract class TextHud extends HudElement {
    protected final BoolSetting showLabel = add(new BoolSetting("showLabel", "Show label", true));
    protected final BoolSetting shadow = add(new BoolSetting("shadow", "Text shadow", true));
    protected final ColorSetting labelColor = add(new ColorSetting("labelColor", "Label colour", 0xFFFFFFFF));
    protected final ColorSetting valueColor = add(new ColorSetting("valueColor", "Value colour", 0xFF55FFFF));

    private final String label;

    protected TextHud(String id, String name, String label, String description) {
        super(id, name, description);
        this.label = label;
    }

    /** Live value, or null when there is nothing to display right now. */
    protected abstract String value(HudContext ctx);

    /** What the editor shows when {@link #value} is null. */
    protected String sample() {
        return "-";
    }

    protected String labelText() {
        return showLabel.get() && !label.isEmpty() ? label + ": " : "";
    }

    private String current(HudContext ctx) {
        String v = value(ctx);
        if (v == null && ctx.editing()) v = sample();
        return v;
    }

    @Override
    public boolean visible(HudContext ctx) {
        return ctx.player() != null && value(ctx) != null;
    }

    @Override
    public int width(HudContext ctx) {
        String v = current(ctx);
        return ctx.textWidth(labelText() + (v == null ? "" : v));
    }

    @Override
    public int height(HudContext ctx) {
        return ctx.lineHeight();
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        String v = current(ctx);
        if (v == null) return;
        String l = labelText();
        int x = 0;
        if (!l.isEmpty()) {
            c.text(l, 0, 0, labelColor.argb(), shadow.get());
            x = ctx.textWidth(l);
        }
        c.text(v, x, 0, valueColor.argb(), shadow.get());
    }
}
