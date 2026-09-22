package dev.dusk.client.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;

/**
 * A module that draws a box on the in-game HUD. Elements draw in their own
 * local space (origin = top-left of the box, unscaled); {@link HudRenderer}
 * applies position, scale and the optional background, and the editor uses
 * {@link #width}/{@link #height} for hit-testing and drag bounds.
 */
public abstract class HudElement extends Module {
    /** Background padding around the element box, in unscaled pixels. */
    public static final int PAD = 2;

    protected final IntSetting scale = add(new IntSetting("scale", "Scale", 100, 50, 300, 5, "%"));
    protected final BoolSetting background = add(new BoolSetting("background", "Background", false));
    protected final ColorSetting backgroundColor = add(new ColorSetting("backgroundColor", "Background colour", 0x80000000));

    protected HudElement(String id, String name, String description) {
        super(id, name, Category.HUD, description);
    }

    public float scale() { return scale.get() / 100f; }

    public void setScalePercent(int percent) { scale.set(percent); }

    public int scalePercent() { return scale.get(); }

    public boolean background() { return background.get(); }

    public int backgroundColor() { return backgroundColor.argb(); }

    /** Unscaled box size; may depend on live data (text width). */
    public abstract int width(HudContext ctx);

    public abstract int height(HudContext ctx);

    public abstract void render(Canvas c, HudContext ctx);

    /** Whether there is anything to draw this frame. The editor ignores this. */
    public boolean visible(HudContext ctx) {
        return ctx.player() != null;
    }

    /** On-screen (scaled) box, including padding when the background is on. */
    public int screenWidth(HudContext ctx) {
        return Math.round((width(ctx) + (background() ? PAD * 2 : 0)) * scale());
    }

    public int screenHeight(HudContext ctx) {
        return Math.round((height(ctx) + (background() ? PAD * 2 : 0)) * scale());
    }
}
