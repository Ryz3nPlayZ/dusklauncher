package dev.dusk.client.render.map;

/**
 * The map's camera: fixed, facing north, tilted down by {@link #PITCH}. A world point lands at
 * {@code u = x}, {@code v = z * sin - y * cos}. Ported from Diorama (MIT, see NOTICE).
 */
public final class MapProjection {
    public static final double PITCH = Math.toRadians(50);
    public static final double SIN = Math.sin(PITCH);
    public static final double COS = Math.cos(PITCH);
    /** Map units across one tile; a unit is one block wide. */
    public static final int TILE_UNITS = 64;
    public static final int PIXELS_PER_UNIT = 4;
    public static final int TILE_PIXELS = TILE_UNITS * PIXELS_PER_UNIT;

    private MapProjection() {
    }

    public static double v(double y, double z) {
        return z * SIN - y * COS;
    }

    /** The z a camera ray at {@code v} crosses height {@code y}. */
    public static double zAt(double v, double y) {
        return (v + y * COS) / SIN;
    }
}
