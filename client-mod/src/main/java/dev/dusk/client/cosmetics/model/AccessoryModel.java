package dev.dusk.client.cosmetics.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A Cosmetica accessory model: the Blockbench "Java block" JSON that
 * Cosmetica hosts, baked into flat quads the way cosmetica-core does
 * (docs/COSMETICS.md §5) so a model that renders there renders identically
 * here.
 *
 * <ul>
 *   <li>positions are the element's {@code from}/{@code to} ÷ 16, after the
 *       optional element rotation (X, then −Y, then Z about {@code origin});</li>
 *   <li>UVs are in 0..16 space and map to the whole texture (fraction =
 *       uv / 16) — {@code texture_size} is ignored, exactly like Cosmetica;</li>
 *   <li>face {@code rotation} shifts which UV corner goes to which vertex;</li>
 *   <li>the vertex order per face is Cosmetica's, so the winding (and the
 *       normal derived from it) match.</li>
 * </ul>
 *
 * The placement (attachment, offset, mirroring, slim shift) is applied by
 * the render layer with the pose stack; the quads here are model-local.
 */
public final class AccessoryModel {
    /** One quad: 4 positions (xyz, model units), 4 uvs (0..1), a unit normal. */
    public record Quad(float[] pos, float[] uv, float nx, float ny, float nz) {}

    private final List<Quad> quads;

    private AccessoryModel(List<Quad> quads) {
        this.quads = quads;
    }

    public List<Quad> quads() {
        return quads;
    }

    public static AccessoryModel parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<Quad> out = new ArrayList<>();
        JsonArray elements = root.has("elements") ? root.getAsJsonArray("elements") : new JsonArray();
        for (JsonElement e : elements) {
            JsonObject el = e.getAsJsonObject();
            Vector3f from = vec(el.getAsJsonArray("from"));
            Vector3f to = vec(el.getAsJsonArray("to"));
            float rx = 0, ry = 0, rz = 0;
            Vector3f origin = new Vector3f();
            if (el.has("rotation")) {
                JsonObject r = el.getAsJsonObject("rotation");
                if (r.has("origin")) origin = vec(r.getAsJsonArray("origin"));
                if (r.has("angle")) {
                    float a = r.get("angle").getAsFloat();
                    switch (r.has("axis") ? r.get("axis").getAsString() : "y") {
                        case "x" -> rx = a;
                        case "z" -> rz = a;
                        default -> ry = a;
                    }
                } else {
                    rx = r.has("x") ? r.get("x").getAsFloat() : 0;
                    ry = r.has("y") ? r.get("y").getAsFloat() : 0;
                    rz = r.has("z") ? r.get("z").getAsFloat() : 0;
                }
            }
            JsonObject faces = el.has("faces") ? el.getAsJsonObject("faces") : new JsonObject();
            for (Face face : Face.values()) {
                if (!faces.has(face.key)) continue;
                JsonObject f = faces.getAsJsonObject(face.key);
                float[] uv;
                if (f.has("uv")) {
                    JsonArray a = f.getAsJsonArray("uv");
                    uv = new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()};
                } else {
                    uv = defaultUv(face, from, to);
                }
                int quadrant = (f.has("rotation") ? f.get("rotation").getAsInt() / 90 : 0) & 3;
                float[] pos = new float[12];
                float[] uvs = new float[8];
                for (int i = 0; i < 4; i++) {
                    Vector3f c = face.corner(i, from, to);
                    c = rotate(c, origin, rx, ry, rz);
                    pos[i * 3] = c.x / 16f;
                    pos[i * 3 + 1] = c.y / 16f;
                    pos[i * 3 + 2] = c.z / 16f;
                    uvs[i * 2] = u(uv, i + quadrant) / 16f;
                    uvs[i * 2 + 1] = v(uv, i + quadrant) / 16f;
                }
                out.add(quad(pos, uvs));
            }
        }
        return new AccessoryModel(List.copyOf(out));
    }

    // ── Cosmetica's FaceInfo vertex order ────────────────────────────────

    private enum Face {
        DOWN("down", new int[][] {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}}),
        UP("up", new int[][] {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}}),
        NORTH("north", new int[][] {{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}}),
        SOUTH("south", new int[][] {{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}}),
        WEST("west", new int[][] {{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}}),
        EAST("east", new int[][] {{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}});

        final String key;
        /** per vertex: 0 = from, 1 = to, for x, y, z */
        final int[][] order;

        Face(String key, int[][] order) {
            this.key = key;
            this.order = order;
        }

        Vector3f corner(int i, Vector3f from, Vector3f to) {
            int[] o = order[i];
            return new Vector3f(o[0] == 0 ? from.x : to.x, o[1] == 0 ? from.y : to.y, o[2] == 0 ? from.z : to.z);
        }
    }

    // BlockFaceUV semantics: index 0 (u0,v0), 1 (u0,v1), 2 (u1,v1), 3 (u1,v0)
    private static float u(float[] uv, int index) {
        return (index % 4) > 1 ? uv[2] : uv[0];
    }

    private static float v(float[] uv, int index) {
        int i = index % 4;
        return i > 0 && i < 3 ? uv[3] : uv[1];
    }

    /** Vanilla's fallback when a face has no uv: the element's footprint. */
    private static float[] defaultUv(Face face, Vector3f from, Vector3f to) {
        return switch (face) {
            case DOWN, UP -> new float[] {from.x, from.z, to.x, to.z};
            case NORTH, SOUTH -> new float[] {from.x, 16 - to.y, to.x, 16 - from.y};
            case WEST, EAST -> new float[] {from.z, 16 - to.y, to.z, 16 - from.y};
        };
    }

    private static Vector3f rotate(Vector3f c, Vector3f o, float rx, float ry, float rz) {
        if (rx != 0) {
            float[] r = rot(c.y, c.z, o.y, o.z, rx);
            c = new Vector3f(c.x, r[0], r[1]);
        }
        if (ry != 0) {
            float[] r = rot(c.x, c.z, o.x, o.z, -ry);
            c = new Vector3f(r[0], c.y, r[1]);
        }
        if (rz != 0) {
            float[] r = rot(c.x, c.y, o.x, o.y, rz);
            c = new Vector3f(r[0], r[1], c.z);
        }
        return c;
    }

    private static float[] rot(float p0, float p1, float o0, float o1, float deg) {
        float a = (float) Math.toRadians(deg);
        float s = Mth.sin(a), co = Mth.cos(a);
        p0 -= o0;
        p1 -= o1;
        return new float[] {p0 * co - p1 * s + o0, p0 * s + p1 * co + o1};
    }

    private static Quad quad(float[] pos, float[] uv) {
        float ax = pos[3] - pos[0], ay = pos[4] - pos[1], az = pos[5] - pos[2];
        float bx = pos[6] - pos[0], by = pos[7] - pos[1], bz = pos[8] - pos[2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return new Quad(pos, uv, 0, 1, 0);
        return new Quad(pos, uv, nx / len, ny / len, nz / len);
    }

    private static Vector3f vec(JsonArray a) {
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }
}
