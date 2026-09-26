package dev.dusk.client.account;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's skin library ({@code <data>/skins/<name>.png} plus
 * {@code skins.json}), shared with its Cosmetics page; outside the launcher
 * a folder in the game's config. Blocking; worker threads only.
 */
public final class SkinLibrary {
    public record Entry(String name, long addedAt, boolean selected) {}

    private SkinLibrary() {}

    public static Path dir() {
        Path data = DuskAccount.dataDir();
        return data != null ? data.resolve("skins") : FabricLoader.getInstance().getConfigDir().resolve("duskclient/skins");
    }

    public static List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        for (JsonElement e : index().getAsJsonArray("skins")) {
            try {
                JsonObject o = e.getAsJsonObject();
                String name = o.get("name").getAsString();
                if (!Files.isRegularFile(dir().resolve(name + ".png"))) continue;
                out.add(new Entry(name, o.has("addedAt") ? o.get("addedAt").getAsLong() : 0,
                        o.has("selected") && o.get("selected").getAsBoolean()));
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    public static byte[] read(String name) throws IOException {
        return Files.readAllBytes(dir().resolve(name + ".png"));
    }

    /** Copy a picked PNG into the library (named after the file, like the launcher's import). */
    public static Entry add(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int[] size = pngSize(bytes);
        if (size == null || size[0] != 64 || (size[1] != 64 && size[1] != 32)) {
            throw new IOException("Skin must be a 64x64 (or legacy 64x32) PNG");
        }
        String stem = file.getFileName().toString();
        int dot = stem.lastIndexOf('.');
        String name = (dot > 0 ? stem.substring(0, dot) : stem).replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isBlank()) name = "skin";
        Files.createDirectories(dir());
        Files.write(dir().resolve(name + ".png"), bytes);
        Entry entry = new Entry(name, System.currentTimeMillis(), false);
        JsonObject index = index();
        JsonArray skins = new JsonArray();
        for (JsonElement e : index.getAsJsonArray("skins")) {
            if (!(e.isJsonObject() && e.getAsJsonObject().has("name") && name.equals(e.getAsJsonObject().get("name").getAsString()))) skins.add(e);
        }
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("addedAt", entry.addedAt());
        o.addProperty("selected", false);
        skins.add(o);
        index.add("skins", skins);
        save(index);
        return entry;
    }

    /** Mark {@code name} as the launcher's selected skin (after it was applied). */
    public static void select(String name) {
        JsonObject index = index();
        for (JsonElement e : index.getAsJsonArray("skins")) {
            if (e.isJsonObject() && e.getAsJsonObject().has("name")) {
                e.getAsJsonObject().addProperty("selected", name.equals(e.getAsJsonObject().get("name").getAsString()));
            }
        }
        try {
            save(index);
        } catch (IOException ignored) {
        }
    }

    private static JsonObject index() {
        try {
            JsonObject o = JsonParser.parseString(Files.readString(dir().resolve("skins.json"))).getAsJsonObject();
            if (o.has("skins") && o.get("skins").isJsonArray()) return o;
        } catch (Exception ignored) {
        }
        JsonObject o = new JsonObject();
        o.add("skins", new JsonArray());
        return o;
    }

    private static void save(JsonObject index) throws IOException {
        Files.createDirectories(dir());
        Files.writeString(dir().resolve("skins.json"), new GsonBuilder().setPrettyPrinting().create().toJson(index));
    }

    /** {width, height} from a PNG's IHDR, or null when it is not a PNG. */
    public static int[] pngSize(byte[] b) {
        if (b.length < 24 || (b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') return null;
        return new int[] {readInt(b, 16), readInt(b, 20)};
    }

    private static int readInt(byte[] b, int i) {
        return (b[i] & 0xFF) << 24 | (b[i + 1] & 0xFF) << 16 | (b[i + 2] & 0xFF) << 8 | (b[i + 3] & 0xFF);
    }

    /** The strips a classic arm paints and a slim arm leaves blank (x, y, w, h at 64 scale). */
    private static final int[][] SLIM_GAPS = {{50, 16, 2, 4}, {54, 20, 2, 12}, {42, 48, 2, 4}, {46, 52, 2, 12}};

    /**
     * Whether a skin has slim arms, read off the PNG the way the launcher's
     * wardrobe does (lib/skin.ts): a square skin whose slim-arm gaps are
     * transparent anywhere, or all black, or all white. Legacy 64x32 skins are classic.
     */
    public static boolean slim(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null || img.getWidth() != img.getHeight() || img.getWidth() < 64) return false;
            int k = img.getWidth() / 64;
            boolean transparent = false, black = true, white = true;
            for (int[] g : SLIM_GAPS) {
                for (int y = g[1] * k; y < (g[1] + g[3]) * k; y++) {
                    for (int x = g[0] * k; x < (g[0] + g[2]) * k; x++) {
                        int argb = img.getRGB(x, y);
                        if (argb >>> 24 != 0xFF) transparent = true;
                        if (argb != 0xFF000000) black = false;
                        if (argb != 0xFFFFFFFF) white = false;
                    }
                }
            }
            return transparent || black || white;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * A legacy 64x32 skin as the 64x64 layout (the left limbs mirrored from
     * the right ones, as the game does when it downloads one); other PNGs as they are.
     */
    public static byte[] modernize(byte[] png) {
        int[] size = pngSize(png);
        if (size == null || size[0] != 64 || size[1] != 32) return png;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) img.setRGB(x, y, src.getRGB(x, y));
            int[][] rects = {
                    {4, 16, 16, 32, 4, 4}, {8, 16, 16, 32, 4, 4}, {0, 20, 24, 32, 4, 12}, {4, 20, 16, 32, 4, 12},
                    {8, 20, 8, 32, 4, 12}, {12, 20, 16, 32, 4, 12}, {44, 16, -8, 32, 4, 4}, {48, 16, -8, 32, 4, 4},
                    {40, 20, 0, 32, 4, 12}, {44, 20, -8, 32, 4, 12}, {48, 20, -16, 32, 4, 12}, {52, 20, -8, 32, 4, 12}};
            for (int[] r : rects) {
                for (int dy = 0; dy < r[5]; dy++) {
                    for (int dx = 0; dx < r[4]; dx++) {
                        img.setRGB(r[0] + r[2] + r[4] - 1 - dx, r[1] + r[3] + dy, src.getRGB(r[0] + dx, r[1] + dy));
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            return png;
        }
    }
}
