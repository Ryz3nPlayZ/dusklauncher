package dev.dusk.client.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The bundled cosmetics catalog: {@code assets/duskclient/cosmetics/registry.json}
 * plus the PNGs next to it, the very same files the 1.21.11 jar and the
 * launcher's wardrobe read, so an id means the same thing everywhere.
 */
public final class CapeRegistry {
    private static final String BASE = "/assets/duskclient/cosmetics/";

    /** One cape. {@code ears}: {@code capes/<id>/ears.png} exists; {@code frameMs}: animation speed. */
    public static final class CapeEntry {
        public final int id;
        public final String name;
        public final boolean glint, upsideDown, ears;
        public final int frameMs;

        CapeEntry(int id, String name, boolean glint, boolean upsideDown, boolean ears, int frameMs) {
            this.id = id;
            this.name = name;
            this.glint = glint;
            this.upsideDown = upsideDown;
            this.ears = ears;
            this.frameMs = frameMs;
        }
    }

    /**
     * One Cosmetica-style accessory: {@code accessories/<id>/model.json} +
     * {@code texture.png}. {@code offset} is in Cosmetica's pixel space,
     * {@code frames}/{@code ticksPerFrame} describe a vertical tilesheet,
     * {@code flags} the hide-with bits.
     */
    public static final class AccessoryEntry {
        public static final int HIDE_WITH_HELMET = 0x1;
        public static final int HIDE_WITH_CHESTPLATE = 0x2;
        public static final int HIDE_WITH_LEGGINGS = 0x4;
        public static final int HIDE_WITH_BOOTS = 0x8;
        public static final int HIDE_WITH_CLOAK = 0x10;

        public final int id;
        public final String name;
        public final Attachment attachment;
        public final float[] offset;
        public final boolean mirrored;
        public final int frames, ticksPerFrame, flags;

        AccessoryEntry(int id, String name, Attachment attachment, float[] offset, boolean mirrored,
                       int frames, int ticksPerFrame, int flags) {
            this.id = id;
            this.name = name;
            this.attachment = attachment;
            this.offset = offset;
            this.mirrored = mirrored;
            this.frames = frames;
            this.ticksPerFrame = ticksPerFrame;
            this.flags = flags;
        }

        public boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    public enum Attachment {
        HEAD, BODY, LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG;

        static Attachment parse(String s) {
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                return BODY;
            }
        }
    }

    private static volatile Map<Integer, CapeEntry> capes;
    private static volatile Map<Integer, AccessoryEntry> accessories;

    private CapeRegistry() {}

    public static Map<Integer, CapeEntry> capes() {
        Map<Integer, CapeEntry> c = capes;
        if (c == null) {
            synchronized (CapeRegistry.class) {
                if (capes == null) capes = load();
                c = capes;
            }
        }
        return c;
    }

    public static CapeEntry cape(int id) {
        return id < 0 ? null : capes().get(id);
    }

    public static Map<Integer, AccessoryEntry> accessories() {
        Map<Integer, AccessoryEntry> a = accessories;
        if (a == null) {
            synchronized (CapeRegistry.class) {
                if (accessories == null) accessories = loadAccessories();
                a = accessories;
            }
        }
        return a;
    }

    public static AccessoryEntry accessory(int id) {
        return id < 0 ? null : accessories().get(id);
    }

    /** Raw bytes of {@code accessories/<id>/<file>}, or null. */
    public static byte[] accessoryFile(int id, String file) {
        return read("accessories/" + id + "/" + file);
    }

    /** Raw PNG bytes for a cape or its ears, or null when the entry ships none. */
    public static byte[] texture(int id, String file) {
        return read("capes/" + id + "/" + file);
    }

    private static byte[] read(String path) {
        try (InputStream in = CapeRegistry.class.getResourceAsStream(BASE + path)) {
            return in == null ? null : Bytes.readAll(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static JsonObject root() {
        try (InputStream in = CapeRegistry.class.getResourceAsStream(BASE + "registry.json")) {
            if (in == null) return null;
            return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<Integer, AccessoryEntry> loadAccessories() {
        JsonObject root = root();
        if (root == null) return Collections.emptyMap();
        try {
            Map<Integer, AccessoryEntry> out = new LinkedHashMap<>();
            JsonArray arr = root.has("accessories") ? root.getAsJsonArray("accessories") : new JsonArray();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                int id = o.get("id").getAsInt();
                float[] offset = new float[3];
                if (o.has("offset")) {
                    JsonArray a = o.getAsJsonArray("offset");
                    for (int i = 0; i < 3 && i < a.size(); i++) offset[i] = a.get(i).getAsFloat();
                }
                out.put(id, new AccessoryEntry(
                        id,
                        o.has("name") ? o.get("name").getAsString() : "Accessory " + id,
                        Attachment.parse(o.has("attachment") ? o.get("attachment").getAsString() : "body"),
                        offset,
                        o.has("mirrored") && o.get("mirrored").getAsBoolean(),
                        Math.max(1, o.has("frames") ? o.get("frames").getAsInt() : 1),
                        Math.max(1, o.has("ticksPerFrame") ? o.get("ticksPerFrame").getAsInt() : 1),
                        o.has("flags") ? o.get("flags").getAsInt() : 0));
            }
            return Collections.unmodifiableMap(out);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private static Map<Integer, CapeEntry> load() {
        JsonObject root = root();
        if (root == null) return Collections.emptyMap();
        try {
            Map<Integer, CapeEntry> out = new LinkedHashMap<>();
            JsonArray arr = root.has("capes") ? root.getAsJsonArray("capes") : new JsonArray();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                int id = o.get("id").getAsInt();
                out.put(id, new CapeEntry(
                        id,
                        o.has("name") ? o.get("name").getAsString() : "Cape " + id,
                        o.has("glint") && o.get("glint").getAsBoolean(),
                        o.has("upsideDown") && o.get("upsideDown").getAsBoolean(),
                        o.has("ears") && o.get("ears").getAsBoolean(),
                        o.has("frameMs") ? Math.max(20, o.get("frameMs").getAsInt()) : CapeTexture.FRAME_MS));
            }
            return Collections.unmodifiableMap(out);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
