package dev.dusk.client.render.skin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * A skin's outer layer (hat, jacket, sleeves, pants) as voxels: every
 * non-transparent texel of the overlay becomes a small block standing on the
 * body part, instead of vanilla's flat shell.
 *
 * <p>Built once per skin from its 64×64 alpha and kept in {@link SkinVoxelCache}.
 * Each voxel shows its texel on every visible side; faces between two
 * voxels and faces against the body are left out, and the strips along a
 * box's edges belong to one face (top/bottom own every edge they touch,
 * front/back the vertical ones) so corners close without two faces fighting.
 *
 * <p>Geometry is in the body part's own space, already in blocks, so a layer
 * only has to {@code part.translateAndRotate} and {@link #emit}.
 */
public final class SkinVoxels {
    public static final int HEAD = 0, BODY = 1, RIGHT_ARM = 2, LEFT_ARM = 3, RIGHT_LEG = 4, LEFT_LEG = 5;
    public static final int PARTS = 6;

    /** Floats per quad: 4 × xyz, then the texel's uv, then the normal. */
    private static final int STRIDE = 12 + 2 + 3;
    private static final float HEAD_DEPTH = 1.0F, BODY_DEPTH = 0.5F;

    private final float[][] quads = new float[PARTS][];

    private SkinVoxels() {}

    /**
     * @param alpha 64×64 alpha, row-major ({@code alpha[y * 64 + x]}, 0–255)
     * @param slim  3-pixel arms
     */
    public static SkinVoxels build(int[] alpha, boolean slim) {
        SkinVoxels v = new SkinVoxels();
        int arm = slim ? 3 : 4;
        v.quads[HEAD] = part(alpha, 32, 0, -4, -8, -4, 8, 8, 8, HEAD_DEPTH);
        v.quads[BODY] = part(alpha, 16, 32, -4, 0, -2, 8, 12, 4, BODY_DEPTH);
        v.quads[RIGHT_ARM] = part(alpha, 40, 32, slim ? -2 : -3, -2, -2, arm, 12, 4, BODY_DEPTH);
        v.quads[LEFT_ARM] = part(alpha, 48, 48, -1, -2, -2, arm, 12, 4, BODY_DEPTH);
        v.quads[RIGHT_LEG] = part(alpha, 0, 32, -2, 0, -2, 4, 12, 4, BODY_DEPTH);
        v.quads[LEFT_LEG] = part(alpha, 0, 48, -2, 0, -2, 4, 12, 4, BODY_DEPTH);
        return v;
    }

    public boolean isEmpty(int part) {
        return quads[part].length == 0;
    }

    /** Draws one part's voxels with the pose already on the body part. */
    public void emit(int part, PoseStack.Pose pose, VertexConsumer vc, int light, int overlay) {
        float[] q = quads[part];
        for (int i = 0; i < q.length; i += STRIDE) {
            float u = q[i + 12], v = q[i + 13], nx = q[i + 14], ny = q[i + 15], nz = q[i + 16];
            for (int k = 0; k < 12; k += 3) {
                vc.addVertex(pose, q[i + k], q[i + k + 1], q[i + k + 2])
                        .setColor(-1)
                        .setUv(u, v)
                        .setOverlay(overlay)
                        .setLight(light)
                        .setNormal(pose, nx, ny, nz);
            }
        }
    }

    // ---- building -----------------------------------------------------------

    private static final int DOWN = 0, UP = 1, WEST = 2, NORTH = 3, EAST = 4, SOUTH = 5;

    /** One face of the box as a grid: where its texels are and how a cell maps into the part. */
    private record Face(int dir, int tu, int tv, int cols, int rows) {}

    private static final class Sink {
        float[] data = new float[STRIDE * 64];
        final float[] corners = new float[12];
        int size;

        void quad(float[] corners, float u, float v, int dir) {
            if (size + STRIDE > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            System.arraycopy(corners, 0, data, size, 12);
            data[size + 12] = u;
            data[size + 13] = v;
            data[size + 14] = dir == WEST ? -1 : dir == EAST ? 1 : 0;
            data[size + 15] = dir == DOWN ? -1 : dir == UP ? 1 : 0;
            data[size + 16] = dir == NORTH ? -1 : dir == SOUTH ? 1 : 0;
            size += STRIDE;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }

    /**
     * Voxels for one box the model defines with {@code addBox(x, y, z, w, h, d)}
     * at texture offset (u, v), using the standard box unwrap.
     */
    private static float[] part(int[] alpha, int u, int v, float x0, float y0, float z0, int w, int h, int d, float t) {
        Face[] faces = {
                new Face(DOWN, u + d, v, w, d),
                new Face(UP, u + d + w, v, w, d),
                new Face(WEST, u, v + d, d, h),
                new Face(NORTH, u + d, v + d, w, h),
                new Face(EAST, u + d + w, v + d, d, h),
                new Face(SOUTH, u + d + d + w, v + d, w, h),
        };
        float x1 = x0 + w, y1 = y0 + h, z1 = z0 + d;
        Sink sink = new Sink();
        float[] box = new float[6];
        for (Face f : faces) {
            for (int j = 0; j < f.rows; j++) {
                for (int i = 0; i < f.cols; i++) {
                    if (!solid(alpha, f, i, j)) continue;
                    cell(f, i, j, x0, y0, z0, x1, y1, z1, t, box);
                    float tu = (f.tu + i + 0.5F) / 64F, tv = (f.tv + j + 0.5F) / 64F;
                    emitVoxel(sink, alpha, f, i, j, box, tu, tv);
                }
            }
        }
        return sink.toArray();
    }

    private static boolean solid(int[] alpha, Face f, int i, int j) {
        if (i < 0 || j < 0 || i >= f.cols || j >= f.rows) return false;
        return alpha[(f.tv + j) * 64 + f.tu + i] != 0;
    }

    /**
     * The voxel of texel (i, j) as {x0, y0, z0, x1, y1, z1}, in pixels. The
     * mapping follows how the model lays each face's texels on the box (model
     * y points down, -z is the front).
     */
    private static void cell(Face f, int i, int j, float x0, float y0, float z0, float x1, float y1, float z1,
                             float t, float[] b) {
        switch (f.dir) {
            case DOWN, UP -> {
                set(b, x0 + i, 0, z1 - j - 1, x0 + i + 1, 0, z1 - j);
                if (f.dir == DOWN) { b[1] = y0 - t; b[4] = y0; } else { b[1] = y1; b[4] = y1 + t; }
                // top and bottom own every edge strip they touch
                if (i == 0) b[0] -= t;
                if (i == f.cols - 1) b[3] += t;
                if (j == f.rows - 1) b[2] -= t;
                if (j == 0) b[5] += t;
            }
            case WEST -> set(b, x0 - t, y0 + j, z1 - i - 1, x0, y0 + j + 1, z1 - i);
            case EAST -> set(b, x1, y0 + j, z0 + i, x1 + t, y0 + j + 1, z0 + i + 1);
            case NORTH, SOUTH -> {
                if (f.dir == NORTH) set(b, x0 + i, y0 + j, z0 - t, x0 + i + 1, y0 + j + 1, z0);
                else set(b, x1 - i - 1, y0 + j, z1, x1 - i, y0 + j + 1, z1 + t);
                // front and back own the vertical edge strips
                boolean low = f.dir == NORTH ? i == 0 : i == f.cols - 1;
                boolean high = f.dir == NORTH ? i == f.cols - 1 : i == 0;
                if (low) b[0] -= t;
                if (high) b[3] += t;
            }
            default -> throw new IllegalStateException();
        }
    }

    private static void set(float[] b, float ax, float ay, float az, float bx, float by, float bz) {
        b[0] = ax; b[1] = ay; b[2] = az; b[3] = bx; b[4] = by; b[5] = bz;
    }

    /**
     * The outer face always; a side only where the neighbouring texel on the
     * same face is empty (or off the face). Never the inner face.
     */
    private static void emitVoxel(Sink sink, int[] alpha, Face f, int i, int j, float[] b, float u, float v) {
        emitFace(sink, b, f.dir, u, v);
        switch (f.dir) {
            case DOWN, UP -> {
                // i runs along +x, j along -z
                if (!solid(alpha, f, i - 1, j)) emitFace(sink, b, WEST, u, v);
                if (!solid(alpha, f, i + 1, j)) emitFace(sink, b, EAST, u, v);
                if (!solid(alpha, f, i, j - 1)) emitFace(sink, b, SOUTH, u, v);
                if (!solid(alpha, f, i, j + 1)) emitFace(sink, b, NORTH, u, v);
            }
            case WEST, EAST -> {
                // WEST: i runs along -z; EAST: along +z. j along +y (down).
                boolean west = f.dir == WEST;
                if (!solid(alpha, f, i - 1, j)) emitFace(sink, b, west ? SOUTH : NORTH, u, v);
                if (!solid(alpha, f, i + 1, j)) emitFace(sink, b, west ? NORTH : SOUTH, u, v);
                if (!solid(alpha, f, i, j - 1)) emitFace(sink, b, DOWN, u, v);
                if (!solid(alpha, f, i, j + 1)) emitFace(sink, b, UP, u, v);
            }
            default -> {
                // NORTH: i runs along +x; SOUTH: along -x
                boolean north = f.dir == NORTH;
                if (!solid(alpha, f, i - 1, j)) emitFace(sink, b, north ? WEST : EAST, u, v);
                if (!solid(alpha, f, i + 1, j)) emitFace(sink, b, north ? EAST : WEST, u, v);
                if (!solid(alpha, f, i, j - 1)) emitFace(sink, b, DOWN, u, v);
                if (!solid(alpha, f, i, j + 1)) emitFace(sink, b, UP, u, v);
            }
        }
    }

    /** One side of box b, wound like the model's own cube faces, in blocks. */
    private static void emitFace(Sink sink, float[] b, int dir, float u, float v) {
        float ax = b[0] / 16F, ay = b[1] / 16F, az = b[2] / 16F, bx = b[3] / 16F, by = b[4] / 16F, bz = b[5] / 16F;
        float[] c = sink.corners;
        switch (dir) {
            case DOWN -> corners(c, bx, ay, bz, ax, ay, bz, ax, ay, az, bx, ay, az);
            case UP -> corners(c, bx, by, az, ax, by, az, ax, by, bz, bx, by, bz);
            case WEST -> corners(c, ax, ay, az, ax, ay, bz, ax, by, bz, ax, by, az);
            case NORTH -> corners(c, bx, ay, az, ax, ay, az, ax, by, az, bx, by, az);
            case EAST -> corners(c, bx, ay, bz, bx, ay, az, bx, by, az, bx, by, bz);
            default -> corners(c, ax, ay, bz, bx, ay, bz, bx, by, bz, ax, by, bz);
        }
        sink.quad(c, u, v, dir);
    }

    private static void corners(float[] c, float... p) {
        System.arraycopy(p, 0, c, 0, 12);
    }
}
