package dev.dusk.client.render.cape;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import static dev.dusk.client.render.cape.CapeSim.NODES;
import static dev.dusk.client.render.cape.CapeSim.SEGMENTS;

/**
 * Draws a cape bent along a {@link CapeSim#shape}: one row of pixels per
 * segment, with the standard cape texture layout (outside at (1, 1), inside
 * at (12, 1), edges around them, 64×32). The pose must already be at the
 * cape's top edge in the body's frame (body pivot, then 2 pixels back).
 */
public final class CapeMesh {
    private CapeMesh() {}

    private static final float HALF_W = 5F, THICK = 1F;

    public static void emit(float[] nodes, PoseStack.Pose pose, VertexConsumer vc, int light, int overlay) {
        // per node: the outward normal (perpendicular to the chain, in the y/z plane)
        float[] n = new float[NODES * 2];
        for (int i = 0; i < NODES; i++) {
            int a = Math.max(0, i - 1) * 3, b = Math.min(SEGMENTS, i + 1) * 3;
            float ty = nodes[b + 1] - nodes[a + 1], tz = nodes[b + 2] - nodes[a + 2];
            float len = (float) Math.sqrt(ty * ty + tz * tz);
            if (len < 1e-5F) { ty = 1; tz = 0; len = 1; }
            n[i * 2] = -tz / len;     // y
            n[i * 2 + 1] = ty / len;  // z
        }
        for (int s = 0; s < SEGMENTS; s++) {
            int a = s * 3, b = a + 3;
            float ax = nodes[a], ay = nodes[a + 1], az = nodes[a + 2];
            float bx = nodes[b], by = nodes[b + 1], bz = nodes[b + 2];
            float any = n[s * 2], anz = n[s * 2 + 1], bny = n[s * 2 + 2], bnz = n[s * 2 + 3];
            // outer surface points
            float aoy = ay + any * THICK, aoz = az + anz * THICK, boy = by + bny * THICK, boz = bz + bnz * THICK;
            float v0 = (1 + s) / 32F, v1 = (2 + s) / 32F;

            // outside (seen from behind): u 1 at +x → 11 at -x
            v(vc, pose, ax - HALF_W, aoy, aoz, 11 / 64F, v0, light, overlay, 0, any, anz);
            v(vc, pose, ax + HALF_W, aoy, aoz, 1 / 64F, v0, light, overlay, 0, any, anz);
            v(vc, pose, bx + HALF_W, boy, boz, 1 / 64F, v1, light, overlay, 0, bny, bnz);
            v(vc, pose, bx - HALF_W, boy, boz, 11 / 64F, v1, light, overlay, 0, bny, bnz);
            // inside (against the back): u 12 at -x → 22 at +x
            v(vc, pose, ax + HALF_W, ay, az, 22 / 64F, v0, light, overlay, 0, -any, -anz);
            v(vc, pose, ax - HALF_W, ay, az, 12 / 64F, v0, light, overlay, 0, -any, -anz);
            v(vc, pose, bx - HALF_W, by, bz, 12 / 64F, v1, light, overlay, 0, -bny, -bnz);
            v(vc, pose, bx + HALF_W, by, bz, 22 / 64F, v1, light, overlay, 0, -bny, -bnz);
            // +x edge (u 0–1)
            v(vc, pose, ax + HALF_W, aoy, aoz, 1 / 64F, v0, light, overlay, 1, 0, 0);
            v(vc, pose, ax + HALF_W, ay, az, 0, v0, light, overlay, 1, 0, 0);
            v(vc, pose, bx + HALF_W, by, bz, 0, v1, light, overlay, 1, 0, 0);
            v(vc, pose, bx + HALF_W, boy, boz, 1 / 64F, v1, light, overlay, 1, 0, 0);
            // -x edge (u 11–12)
            v(vc, pose, ax - HALF_W, ay, az, 12 / 64F, v0, light, overlay, -1, 0, 0);
            v(vc, pose, ax - HALF_W, aoy, aoz, 11 / 64F, v0, light, overlay, -1, 0, 0);
            v(vc, pose, bx - HALF_W, boy, boz, 11 / 64F, v1, light, overlay, -1, 0, 0);
            v(vc, pose, bx - HALF_W, by, bz, 12 / 64F, v1, light, overlay, -1, 0, 0);
            if (s == 0) {
                // top edge (u 1–11, v 0–1), facing back up the chain
                float uy = -anz, uz = any;
                v(vc, pose, ax - HALF_W, ay, az, 11 / 64F, 1 / 32F, light, overlay, 0, uy, uz);
                v(vc, pose, ax + HALF_W, ay, az, 1 / 64F, 1 / 32F, light, overlay, 0, uy, uz);
                v(vc, pose, ax + HALF_W, aoy, aoz, 1 / 64F, 0, light, overlay, 0, uy, uz);
                v(vc, pose, ax - HALF_W, aoy, aoz, 11 / 64F, 0, light, overlay, 0, uy, uz);
            }
            if (s == SEGMENTS - 1) {
                // bottom edge (u 11–21, v 0–1)
                float ty = bnz, tz = -bny; // tangent at the last node
                v(vc, pose, bx + HALF_W, by, bz, 11 / 64F, 0, light, overlay, 0, ty, tz);
                v(vc, pose, bx - HALF_W, by, bz, 21 / 64F, 0, light, overlay, 0, ty, tz);
                v(vc, pose, bx - HALF_W, boy, boz, 21 / 64F, 1 / 32F, light, overlay, 0, ty, tz);
                v(vc, pose, bx + HALF_W, boy, boz, 11 / 64F, 1 / 32F, light, overlay, 0, ty, tz);
            }
        }
    }

    private static void v(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                          int light, int overlay, float nx, float ny, float nz) {
        vc.addVertex(pose, x / 16F, y / 16F, z / 16F)
                .setColor(-1)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
