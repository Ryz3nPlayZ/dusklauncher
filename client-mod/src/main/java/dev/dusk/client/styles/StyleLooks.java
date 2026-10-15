package dev.dusk.client.styles;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Looks: a category's textures recoloured, whatever pack they come from (the
 * category's pick, a resource pack or the game's own). The recolours and the
 * any-colour formula are Awerial's Pack Editor's (MIT, see NOTICE); the look
 * is baked into a small pack that loads above everything else.
 */
public final class StyleLooks {
    /** A recolour: {@code id} is stored in the config, {@code swatch} shows it on the page. */
    public record Look(String id, String name, int swatch, IntUnaryOperator op) {}

    public static final List<Look> PRESETS = List.of(
            new Look("crimson", "Crimson", 0xFFD02A2A, colorize(0, 0.75f, 1.05f)),
            new Look("frost", "Frost", 0xFF7FD3F5, colorize(195, 0.55f, 1.25f)),
            new Look("toxic", "Toxic", 0xFF63D62B, colorize(105, 0.85f, 1.1f)),
            new Look("royal", "Royal", 0xFF9B4DE0, colorize(275, 0.7f, 1.1f)),
            new Look("gilded", "Gilded", 0xFFF2B630, colorize(45, 0.8f, 1.2f)),
            new Look("sakura", "Sakura", 0xFFF59CC8, colorize(330, 0.5f, 1.3f)),
            new Look("ocean", "Ocean", 0xFF2F6CE0, colorize(220, 0.8f, 1.0f)),
            new Look("midnight", "Midnight", 0xFF33335C, colorize(240, 0.35f, 0.6f)),
            new Look("neon", "Neon", 0xFF39FFB0, StyleLooks::neon),
            new Look("pastel", "Pastel", 0xFFF3D6E6, StyleLooks::pastel),
            new Look("mono", "Mono", 0xFF9A9A9A, StyleLooks::mono),
            new Look("inverted", "Inverted", 0xFF202020, c -> c & 0xFF000000 | ~c & 0xFFFFFF));

    /** The vanilla sky pictures a sky look also recolours (the sun, the moon's phases, the End sky). */
    private static final Pattern VANILLA_SKY = Pattern.compile("^textures/environment/(sun|moon_phases|end_sky|celestial/)");

    private StyleLooks() {}

    /** A preset by id, or a custom colour stored as "#RRGGBB"; null for none. */
    @Nullable
    public static Look byId(@Nullable String id) {
        if (id == null || id.isEmpty()) return null;
        for (Look l : PRESETS) if (l.id.equals(id)) return l;
        if (id.startsWith("#") && id.length() == 7) {
            try {
                int rgb = Integer.parseInt(id.substring(1), 16);
                return new Look(id, "Custom", 0xFF000000 | rgb, c -> recolour(c, rgb));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    public static String customId(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /** Whether {@code rel} (a path under assets/minecraft/) is a picture the look for {@code category} recolours. */
    static boolean covers(String category, String rel) {
        if (!rel.endsWith(".png")) return false;
        if (category.equals(StyleFiles.category(rel))) return true;
        return category.equals("sky") && VANILLA_SKY.matcher(rel).find();
    }

    /**
     * Writes the look pack for {@code category}: the topmost copy of each
     * picture it covers across {@code packs} (lowest first, as loaded),
     * recoloured, with its animation file from the same pack. Returns false
     * when there was nothing to recolour.
     */
    static boolean build(Path out, String category, Look look, List<PackResources> packs) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int i = packs.size() - 1; i >= 0; i--) {
            Map<String, byte[]> mine = new HashMap<>();
            for (String root : category.equals("sky") ? new String[] {"textures", "optifine", "mcpatcher"} : new String[] {"textures"}) {
                try {
                    packs.get(i).listResources(PackType.CLIENT_RESOURCES, "minecraft", root, (id, in) -> {
                        String rel = id.getPath();
                        String png = rel.endsWith(".png.mcmeta") ? rel.substring(0, rel.length() - ".mcmeta".length()) : rel;
                        if (files.containsKey(StyleFiles.MC + png) || !covers(category, png)) return;
                        try (InputStream s = in.get()) {
                            mine.put(rel, s.readAllBytes());
                        } catch (IOException ignored) {
                        }
                    });
                } catch (RuntimeException ignored) {
                    // a broken pack lists nothing
                }
            }
            for (Map.Entry<String, byte[]> e : mine.entrySet()) {
                String rel = e.getKey();
                if (!rel.endsWith(".png")) continue;
                byte[] png = recolour(e.getValue(), look.op);
                if (png == null) continue;
                files.put(StyleFiles.MC + rel, png);
                byte[] anim = mine.get(rel + ".mcmeta");
                if (anim != null) files.put(StyleFiles.MC + rel + ".mcmeta", anim);
            }
        }
        if (files.isEmpty()) return false;

        Files.createDirectories(out.getParent());
        Path tmp = Files.createTempFile(out.getParent(), "look-", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
                zip.putNextEntry(new ZipEntry("pack.mcmeta"));
                zip.write(StyleFiles.PACK_INFO.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                for (Map.Entry<String, byte[]> e : files.entrySet()) {
                    zip.putNextEntry(new ZipEntry(e.getKey()));
                    zip.write(e.getValue());
                    zip.closeEntry();
                }
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return true;
    }

    @Nullable
    private static byte[] recolour(byte[] png, IntUnaryOperator op) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
            if (src == null) return null;
            BufferedImage img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
            int[] px = src.getRGB(0, 0, src.getWidth(), src.getHeight(), null, 0, src.getWidth());
            for (int i = 0; i < px.length; i++) if (px[i] >>> 24 != 0) px[i] = op.applyAsInt(px[i]);
            img.setRGB(0, 0, img.getWidth(), img.getHeight(), px, 0, img.getWidth());
            ByteArrayOutputStream out = new ByteArrayOutputStream(png.length);
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Recolours one ARGB pixel for the swatch previews on the page. */
    public static int preview(Look look, int argb) {
        return look.op.applyAsInt(argb);
    }

    // ---- the recolours (Awerial's Pack Editor) ------------------------------------

    private static IntUnaryOperator colorize(float hue, float sat, float bright) {
        return c -> {
            float[] hsv = toHsv(c);
            float s = Math.min(1, hsv[1] * 0.4f + sat * 0.6f);
            float v = Math.min(1, hsv[2] * bright);
            return fromHsv(c, hue / 360f, s, v);
        };
    }

    private static int neon(int c) {
        float[] hsv = toHsv(c);
        return fromHsv(c, hsv[0], Math.min(1, hsv[1] * 1.8f + 0.3f), Math.min(1, hsv[2] * 1.35f + 0.1f));
    }

    private static int pastel(int c) {
        float[] hsv = toHsv(c);
        return fromHsv(c, hsv[0], hsv[1] * 0.4f, Math.min(1, hsv[2] * 0.6f + 0.4f));
    }

    private static int mono(int c) {
        int l = (int) (((c >> 16) & 0xFF) * 0.299 + ((c >> 8) & 0xFF) * 0.587 + (c & 0xFF) * 0.114);
        return c & 0xFF000000 | l << 16 | l << 8 | l;
    }

    /** Any colour: the target's hue, saturation scaled by the pixel's, brightness kept from the pixel. */
    static int recolour(int argb, int target) {
        float[] t = toHsv(target);
        float[] p = toHsv(argb);
        float tmax = t[2];
        float s = Math.min(1, t[1] * (0.35f + 0.65f * p[1]));
        float v = Math.min(1, p[2] * (0.55f + 0.45f * tmax));
        return fromHsv(argb, t[0], s, v);
    }

    private static float[] toHsv(int c) {
        float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h;
        if (d == 0) h = 0;
        else if (max == r) h = ((g - b) / d) % 6;
        else if (max == g) h = (b - r) / d + 2;
        else h = (r - g) / d + 4;
        h /= 6;
        if (h < 0) h++;
        return new float[] {h, max == 0 ? 0 : d / max, max};
    }

    private static int fromHsv(int original, float h, float s, float v) {
        float hh = (h % 1f) * 6;
        int i = (int) Math.floor(hh);
        float f = hh - i, p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return original & 0xFF000000 | Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }
}
