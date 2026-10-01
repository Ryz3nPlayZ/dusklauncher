package dev.dusk.client.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * Where a world position lands on screen this frame. The world-render mixin
 * hands over the camera's own matrices (so view bobbing, hurt shake and zoom
 * are all in them) and the HUD projects through them later in the frame.
 */
public final class WorldProjection {
    private WorldProjection() {}

    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static final Vector4f SCRATCH = new Vector4f();
    private static double camX, camY, camZ;
    private static boolean ready;

    /** Called at the start of each world render with the camera-relative matrices. */
    public static void capture(Matrix4fc modelView, Matrix4fc projection, double x, double y, double z) {
        VIEW_PROJ.set(projection).mul(modelView);
        camX = x;
        camY = y;
        camZ = z;
        ready = true;
    }

    public static boolean ready() { return ready; }

    public static double camX() { return camX; }

    public static double camY() { return camY; }

    public static double camZ() { return camZ; }

    /**
     * Screen position of a world point in GUI pixels, written to {@code out}
     * as {x, y}. False when the point is behind the camera; a point off to the
     * side still gets its (off-screen) position.
     */
    public static boolean project(double x, double y, double z, int guiW, int guiH, float[] out) {
        if (!ready) return false;
        Vector4f v = SCRATCH.set((float) (x - camX), (float) (y - camY), (float) (z - camZ), 1F);
        VIEW_PROJ.transform(v);
        if (v.w <= 0.05F) return false;
        out[0] = (v.x / v.w * 0.5F + 0.5F) * guiW;
        out[1] = (0.5F - v.y / v.w * 0.5F) * guiH;
        return true;
    }
}
