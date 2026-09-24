package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;

/**
 * Hitboxes: a port of sootysplash's Combat Hitboxes. Draws entity hitboxes
 * without F3+B and replaces vanilla's box pass with a PvP-oriented one:
 * the box recolours while you are aiming at the entity or while it is
 * hurt, the eye-height slab and look direction are optional, the stroke
 * width switches past a distance, and an outline can sit behind the box.
 * Stuck arrows and bee stingers can be hidden from players.
 *
 * <p>Widths are percentages of vanilla's 2.5px stroke. The integrated
 * server's own box pass (singleplayer, green) stays vanilla, as in the
 * original mod.
 */
public class Hitbox extends Module {
    /** Vanilla's stroke width ({@code GizmoStyle.stroke(int)}). */
    public static final float VANILLA_WIDTH = 2.5f;

    private static Hitbox instance;

    public final ColorSetting boxColor = add(new ColorSetting("color", "Box colour", 0xFFFFFFFF));
    public final BoolSetting changeTargetColor = add(new BoolSetting("targetColor", "Colour targeted entity", true));
    public final ColorSetting targetColor = add(new ColorSetting("targetBoxColor", "Target colour", 0xFFFF0000));
    public final BoolSetting hurtColor = add(new BoolSetting("hurtColor", "Colour hurt entities", false));
    public final ColorSetting hurtBoxColor = add(new ColorSetting("hurtBoxColor", "Hurt colour", 0xFFFF00FF));
    public final BoolSetting eyeHeight = add(new BoolSetting("eyeHeight", "Eye height", true));
    public final ColorSetting eyeColor = add(new ColorSetting("eyeColor", "Eye height colour", 0xFFFF0000));
    public final BoolSetting lookDir = add(new BoolSetting("lookDir", "Look direction", true));
    public final BoolSetting lookLine = add(new BoolSetting("lookLine", "Look direction as line", true));
    public final ColorSetting lookColor = add(new ColorSetting("lookColor", "Look direction colour", 0xFF0000FF));
    public final IntSetting width = add(new IntSetting("lineScale", "Line width", 100, 20, 400, 10, "%"));
    public final IntSetting farDistance = add(new IntSetting("farDistance", "Far width after", 32, 1, 128, 1, " blocks"));
    public final IntSetting farWidth = add(new IntSetting("farLineScale", "Far line width", 100, 20, 400, 10, "%"));
    public final BoolSetting outline = add(new BoolSetting("outline", "Outline", false));
    public final ColorSetting outlineColor = add(new ColorSetting("outlineColor", "Outline colour", 0xFF000000));
    public final IntSetting outlineScale = add(new IntSetting("outlineScale", "Outline width", 200, 100, 500, 10, "%"));
    public final BoolSetting hideArrows = add(new BoolSetting("hideArrows", "Hide stuck arrows", false));
    public final BoolSetting hideFireworks = add(new BoolSetting("hideFireworks", "Skip fireworks", false));
    public final BoolSetting hideItems = add(new BoolSetting("hideItems", "Skip items", false));

    public Hitbox() {
        super("hitbox", "Hitboxes", Category.RENDER,
                "Combat hitboxes: target/hurt colours, eye height, look direction.");
        instance = this;
    }

    /** Null until the module is registered (it is 1.21.11+ only). */
    public static Hitbox instance() {
        return instance;
    }

    public static boolean active() {
        Hitbox m = instance;
        return m != null && m.enabled();
    }

    public static boolean hidesArrows() {
        Hitbox m = instance;
        return m != null && m.enabled() && m.hideArrows.get();
    }

    /** Stroke width for an entity this far from the player. */
    public float lineWidth(double distance) {
        IntSetting scale = distance > farDistance.get() ? farWidth : width;
        return VANILLA_WIDTH * scale.get() / 100f;
    }

    /**
     * Injection anchors for the version-layer {@code HitboxToggleMixin},
     * which rebuilds the debug-gizmo list on toggle. They stay empty here:
     * the gizmo list moved between versions (26.2 keeps it on the level
     * extractor), so only version code may touch it.
     */
    @Override
    protected void onEnable() {}

    @Override
    protected void onDisable() {}
}
