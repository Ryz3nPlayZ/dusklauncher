package dev.dusk.client.render;

/**
 * The bridge between the Damage Tint module and the game's overlay texture:
 * the sheet the entity renderers sample the red hurt flash from. The texture
 * itself is version-specific, so the mixin over it registers here and the
 * module pushes colours through without naming a game class.
 */
public final class OverlayTint {
    /** The overlay sheet is 16 columns wide; one per damage type, plus spares. */
    public static final int COLUMNS = 16;

    public static final int OTHER = 0;
    public static final int MELEE = 1;
    public static final int RANGED = 2;
    public static final int MAGIC = 3;
    public static final int CRIT = 4;
    public static final int MACE = 5;
    public static final int EXPLOSION = 6;

    /** Implemented by the per-version OverlayTexture mixin. */
    public interface Sink {
        void duskclient$setOverlayColors(int[] argbByColumn, boolean fade);
    }

    private static Sink sink;

    private OverlayTint() {}

    public static void bind(Sink texture) {
        sink = texture;
    }

    /** Repaints the red half of the sheet; a no-op before the texture exists. */
    public static void apply(int[] argbByColumn, boolean fade) {
        Sink target = sink;
        if (target != null) target.duskclient$setOverlayColors(argbByColumn, fade);
    }
}
