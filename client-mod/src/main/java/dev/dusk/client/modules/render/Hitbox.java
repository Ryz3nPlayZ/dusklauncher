package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;

/**
 * Hitboxes: draws entity hitboxes without needing F3+B. Vanilla's own
 * hitbox pass ({@code EntityHitboxDebugRenderer}) does the drawing, so the
 * boxes match the debug renderer exactly: the entity box, the integrated-
 * server box and vehicle boxes, plus the name labels.
 *
 * <p>The module only forces the debug entry on and repaints the strokes;
 * with the module off (or the colour at vanilla white) F3+B behaves exactly
 * as vanilla. Per-part colours are not ported: vanilla codes white/red/blue
 * by box kind, and the editor has a single colour picker, so one colour
 * repaints every stroke.
 */
public class Hitbox extends Module {
    /** Vanilla's box white, the default stroke. */
    public static final int VANILLA = 0xFFFFFFFF;

    private static Hitbox instance;

    private final ColorSetting color = add(new ColorSetting("color", "Box colour", VANILLA));
    private final IntSetting width = add(new IntSetting("lineWidth", "Line width", 1, 1, 5, 1, "px"));

    public Hitbox() {
        super("hitbox", "Hitboxes", Category.RENDER,
                "Draws entity hitboxes without needing F3+B.");
        instance = this;
    }

    public static Hitbox instance() {
        return instance;
    }

    public static boolean active() {
        Hitbox m = instance;
        return m != null && m.enabled();
    }

    /** ARGB, as vanilla's {@code GizmoStyle.stroke} takes it. */
    public static int boxColor() {
        Hitbox m = instance;
        return m == null ? VANILLA : m.color.argb();
    }

    public static float lineWidth() {
        Hitbox m = instance;
        return m == null ? 1f : m.width.get();
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
