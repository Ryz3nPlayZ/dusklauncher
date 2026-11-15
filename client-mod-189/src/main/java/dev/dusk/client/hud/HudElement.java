package dev.dusk.client.hud;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;

/** A module drawn on the HUD at a draggable position, with the shared scale and background settings. */
public abstract class HudElement extends Module {
    public static final int PAD = 2;

    protected final IntSetting scale;
    protected final BoolSetting background;
    protected final ColorSetting backgroundColor;
    protected final BoolSetting hideInF3;

    protected HudElement(String id, String name, String description) {
        this(id, name, description, false);
    }

    protected HudElement(String id, String name, String description, boolean backgroundByDefault) {
        super(id, name, Category.HUD, description);
        scale = add(new IntSetting("scale", "Scale", 100, 50, 300, 5, "%"));
        background = add(new BoolSetting("background", "Background", backgroundByDefault));
        backgroundColor = add(new ColorSetting("backgroundColor", "Background colour", 0x7F313131));
        hideInF3 = add(new BoolSetting("hideInF3", "Hide in F3", true));
    }

    public float scale() { return scale.get() / 100f; }
    public void setScalePercent(int percent) { scale.set(percent); }
    public int scalePercent() { return scale.get(); }
    public boolean background() { return background.get(); }
    public int backgroundColor() { return backgroundColor.argb(); }
    public boolean hideInF3() { return hideInF3.get(); }

    public void beforeDraw(HudContext ctx) {}

    public abstract int width(HudContext ctx);

    public abstract int height(HudContext ctx);

    public abstract void render(Canvas c, HudContext ctx);

    public boolean visible(HudContext ctx) { return ctx.player() != null; }

    public int screenWidth(HudContext ctx) {
        return Math.round((width(ctx) + (background() ? PAD * 2 : 0)) * scale());
    }

    public int screenHeight(HudContext ctx) {
        return Math.round((height(ctx) + (background() ? PAD * 2 : 0)) * scale());
    }
}
