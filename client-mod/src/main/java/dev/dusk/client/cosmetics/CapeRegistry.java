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
import java.util.Map;

/**
 * The bundled cosmetics catalog: {@code assets/duskclient/cosmetics/registry.json}
 * plus the PNGs next to it. This jar is the single source of truth for what
 * exists — the launcher reads the very same files out of the jar for the
 * wardrobe, and a server (phase 3) would only ever exchange ids.
 */
public final class CapeRegistry {
    private static final String BASE = "/assets/duskclient/cosmetics/";

    /**
     * One cape entry. {@code ears} means {@code capes/<id>/ears.png} exists;
     * {@code frameMs} is the animation speed (100 = MinecraftCapes default).
     */
    public record CapeEntry(int id, String name, boolean glint, boolean upsideDown, boolean ears, int frameMs) {}

    /**
     * One Cosmetica-style accessory: {@code accessories/<id>/model.json} +
     * {@code texture.png}. {@code offset} is in Cosmetica's pixel space (the
     * numbers the Cosmetica API reports), {@code frames}/{@code ticksPerFrame}
     * describe a vertical tilesheet, {@code flags} the hide-with bits
     * (docs/COSMETICS.md §5).
     */
    public record AccessoryEntry(int id, String name, Attachment attachment, float[] offset, boolean mirrored,
                                 int frames, int ticksPerFrame, int flags) {
        public static final int HIDE_WITH_HELMET = 0x1;
        public static final int HIDE_WITH_CHESTPLATE = 0x2;
        public static final int HIDE_WITH_LEGGINGS = 0x4;
        public static final int HIDE_WITH_BOOTS = 0x8;
        public static final int HIDE_WITH_CLOAK = 0x10;
        public static final int HIDE_WITH_ELYTRA = 0x20;
        public static final int HIDE_WITH_PARROT = 0x40;

        public boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    public enum Attachment {
        HEAD, BODY, LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG;

        static Attachment parse(String s) {
            try {
                return valueOf(s.trim().toUpperCase().replace('-', '_'));
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
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static JsonObject root() {
        try (InputStream in = CapeRegistry.class.getResourceAsStream(BASE + "registry.json")) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
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
