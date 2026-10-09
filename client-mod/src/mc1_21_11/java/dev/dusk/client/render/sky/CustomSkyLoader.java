package dev.dusk.client.render.sky;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a resource pack's OptiFine/MCPatcher sky layers and reads each
 * {@code skyN.properties} the way OptiFine does. A layer whose texture is
 * missing, or whose fade times don't make a day, is left out — as OptiFine
 * leaves it out.
 */
final class CustomSkyLoader {
    private static final Pattern FILE = Pattern.compile("(?:optifine|mcpatcher)/sky/(world-?\\d+)/sky(\\d+)\\.properties$");
    private static final Pattern SIGNED_RANGE = Pattern.compile("(\\d|\\))-(\\d|\\()");
    private static final String[] BLENDS = {"alpha", "add", "subtract", "multiply", "dodge", "burn", "screen", "overlay", "replace"};

    /** world0 layers then world1 (End) layers, each in skyN order. */
    record Skies(List<SkyLayer> overworld, List<SkyLayer> end) {}

    private record Found(Identifier id, int world, int number) {}

    private CustomSkyLoader() {}

    static Skies load(ResourceManager resources) {
        List<Found> found = new ArrayList<>();
        for (String root : new String[]{"optifine/sky", "mcpatcher/sky"}) {
            Map<Identifier, Resource> files = resources.listResources(root, id -> id.getPath().endsWith(".properties"));
            for (Identifier id : files.keySet()) {
                Matcher m = FILE.matcher(id.getPath());
                if (!m.find()) continue;
                int world = switch (m.group(1)) {
                    case "world0" -> 0;
                    case "world1" -> 1;
                    default -> -1; // the Nether has no sky to draw on
                };
                if (world < 0) continue;
                try {
                    found.add(new Found(id, world, Integer.parseInt(m.group(2))));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // OptiFine's own folder before MCPatcher's, then by number
        found.sort(Comparator.comparingInt(Found::number));
        List<SkyLayer> overworld = new ArrayList<>(), end = new ArrayList<>();
        for (Found f : found) {
            SkyLayer layer = read(resources, f.id());
            if (layer != null) (f.world() == 0 ? overworld : end).add(layer);
        }
        return new Skies(List.copyOf(overworld), List.copyOf(end));
    }

    private static SkyLayer read(ResourceManager resources, Identifier id) {
        Properties p = new Properties();
        try (InputStream in = resources.getResource(id).orElseThrow().open()) {
            p.load(in);
        } catch (Exception e) {
            return null;
        }
        Identifier texture = texture(resources, p.getProperty("source"), id);
        if (texture == null) return null;

        int[] fade = fade(p);
        if (fade == null) return null;
        float speed = parseFloat(p.getProperty("speed"), 1);
        if (speed < 0) return null;
        int daysLoop = 8;
        if (p.containsKey("daysLoop")) {
            daysLoop = parseInt(p.getProperty("daysLoop"), 8);
            if (daysLoop <= 0) return null;
        }
        int[][] days = p.containsKey("days") ? ranges(p.getProperty("days"), false) : null;

        float[] axis = {1, 0, 0};
        float[] given = axis(p.getProperty("axis"));
        if (given != null) axis = new float[]{given[2], given[1], -given[0]};

        boolean clear = true, rain = false, thunder = false;
        if (p.containsKey("weather")) {
            List<String> weather = tokens(p.getProperty("weather"), " ");
            clear = weather.contains("clear");
            rain = weather.contains("rain");
            thunder = weather.contains("thunder");
        }

        boolean biomeCondition = p.containsKey("biomes"), biomeInclusion = true;
        List<Identifier> biomes = new ArrayList<>();
        if (biomeCondition) {
            String raw = p.getProperty("biomes", "").trim();
            if (raw.startsWith("!")) {
                biomeInclusion = false;
                raw = raw.substring(1);
            }
            for (String name : tokens(raw, " ")) {
                Identifier biome = biomeId(name);
                if (biome != null) biomes.add(biome);
            }
        }
        int[][] heights = p.containsKey("heights") ? ranges(p.getProperty("heights"), true) : null;

        return new SkyLayer(texture, blend(p.getProperty("blend")), fade.length == 0,
                fade.length == 0 ? 0 : fade[0], fade.length == 0 ? 0 : fade[1],
                fade.length == 0 ? 0 : fade[2], fade.length == 0 ? 0 : fade[3],
                parseBoolean(p.getProperty("rotate"), true), speed, axis, clear, rain, thunder,
                parseFloat(p.getProperty("transition"), 1),
                biomeCondition, biomeInclusion, List.copyOf(biomes),
                heights != null, heights == null ? new int[0][] : heights,
                days == null ? new int[0][] : days, daysLoop);
    }

    /** {@code {}} for always on, the four normalised times, or null for a fade OptiFine rejects. */
    private static int[] fade(Properties p) {
        boolean sfi = p.containsKey("startFadeIn"), efi = p.containsKey("endFadeIn"),
                sfo = p.containsKey("startFadeOut"), efo = p.containsKey("endFadeOut");
        if (!sfi && !efi && !sfo && !efo) return new int[0];
        if (!sfi || !efi || !efo) return null;
        int startFadeIn = clockTime(p.getProperty("startFadeIn"));
        int endFadeIn = clockTime(p.getProperty("endFadeIn"));
        int endFadeOut = clockTime(p.getProperty("endFadeOut"));
        if (startFadeIn < 0 || endFadeIn < 0 || endFadeOut < 0) return null;
        int fadeIn = SkyLayer.normalizeTime(endFadeIn - startFadeIn);
        int startFadeOut = sfo ? clockTime(p.getProperty("startFadeOut")) : -1;
        if (startFadeOut < 0) {
            startFadeOut = SkyLayer.normalizeTime(endFadeOut - fadeIn);
            if (SkyLayer.containsTime(startFadeOut, startFadeIn, endFadeIn)) startFadeOut = endFadeIn;
        }
        int cycle = fadeIn + SkyLayer.normalizeTime(startFadeOut - endFadeIn)
                + SkyLayer.normalizeTime(endFadeOut - startFadeOut)
                + SkyLayer.normalizeTime(startFadeIn - endFadeOut);
        if (cycle != 0 && cycle != SkyLayer.DAY) return null;
        return new int[]{startFadeIn, endFadeIn, startFadeOut, endFadeOut};
    }

    /** "hh:mm" as day ticks (06:00 is tick 0), or -1. */
    private static int clockTime(String time) {
        if (time == null) return -1;
        String[] parts = time.split(":");
        if (parts.length != 2) return -1;
        int hour = parseInt(parts[0], -1), minute = parseInt(parts[1], -1);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return -1;
        hour -= 6;
        if (hour < 0) hour += 24;
        return hour * 1000 + (int) (minute / 60.0 * 1000.0);
    }

    static int blend(String name) {
        if (name != null) {
            String n = name.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < BLENDS.length; i++) {
                if (BLENDS[i].equals(n)) return i;
            }
        }
        return CustomSkyEngine.ADD;
    }

    static String blendName(int blend) {
        return BLENDS[blend];
    }

    // where the texture is: OptiFine's path rules

    private static Identifier texture(ResourceManager resources, String source, Identifier properties) {
        String file = properties.getPath().substring(properties.getPath().lastIndexOf('/') + 1);
        Identifier id = textureId(source != null ? source : "./" + file.replace(".properties", ".png"), properties);
        return id != null && resources.getResource(id).isPresent() ? id : null;
    }

    private static Identifier textureId(String source, Identifier properties) {
        String path = source.trim();
        if (path.isEmpty()) return null;
        if (path.startsWith("minecraft:")) return Identifier.tryBuild("minecraft", png(path.substring("minecraft:".length())));
        String dir = properties.getPath().substring(0, Math.max(0, properties.getPath().lastIndexOf('/')));
        if (path.startsWith("assets/minecraft/")) {
            path = path.substring("assets/minecraft/".length());
        } else if (path.startsWith("./")) {
            path = dir + "/" + path.substring(2);
        } else {
            if (path.startsWith("/~")) path = path.substring(1);
            if (path.startsWith("~/")) path = "optifine/" + path.substring(2);
            else if (path.startsWith("/")) path = "optifine/" + path.substring(1);
        }
        path = png(path);
        String[] asset = path.split("/", 3);
        if (asset.length == 3 && asset[0].equals("assets")) return Identifier.tryBuild(asset[1], asset[2]);
        if (path.contains(":")) return Identifier.tryParse(path);
        return Identifier.tryBuild(properties.getNamespace(), path);
    }

    private static String png(String path) {
        return path.endsWith(".png") ? path : path + ".png";
    }

    // the property value parsers OptiFine uses

    private static List<String> tokens(String source, String delimiters) {
        List<String> out = new ArrayList<>();
        if (source == null) return out;
        StringTokenizer t = new StringTokenizer(source.trim(), delimiters);
        while (t.hasMoreTokens()) out.add(t.nextToken());
        return out;
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) return fallback;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("true") || !v.equals("false") && fallback;
    }

    private static float parseFloat(String value, float fallback) {
        if (value == null) return fallback;
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static float[] axis(String value) {
        List<String> parts = tokens(value, " ");
        if (parts.size() != 3) return null;
        float[] axis = new float[3];
        for (int i = 0; i < 3; i++) {
            axis[i] = parseFloat(parts.get(i), Float.NaN);
            if (Float.isNaN(axis[i])) return null;
        }
        return axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2] < 0.00001f ? null : axis;
    }

    private static Identifier biomeId(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains(":") ? Identifier.tryParse(n) : Identifier.tryBuild("minecraft", n);
    }

    /** "1-3 7" style ranges; heights may be negative ("(-64)-0"). Null when any part doesn't parse. */
    private static int[][] ranges(String source, boolean signed) {
        List<int[]> out = new ArrayList<>();
        for (String part : tokens(source, " ,")) {
            int[] r = signed ? signedRange(part) : unsignedRange(part);
            if (r == null) return null;
            out.add(r);
        }
        return out.toArray(new int[0][]);
    }

    private static int[] unsignedRange(String value) {
        if (value.contains("-")) {
            String[] parts = value.split("-");
            if (parts.length != 2) return null;
            int a = parseInt(parts[0], -1), b = parseInt(parts[1], -1);
            return a >= 0 && b >= 0 ? new int[]{Math.min(a, b), Math.max(a, b)} : null;
        }
        int v = parseInt(value, -1);
        return v >= 0 ? new int[]{v, v} : null;
    }

    private static int[] signedRange(String value) {
        if (value.contains("=")) return null;
        String split = SIGNED_RANGE.matcher(value).replaceAll("$1=$2");
        if (split.contains("=")) {
            String[] parts = split.split("=");
            if (parts.length != 2) return null;
            int a = parseInt(unbracket(parts[0]), Integer.MIN_VALUE), b = parseInt(unbracket(parts[1]), Integer.MIN_VALUE);
            return a != Integer.MIN_VALUE && b != Integer.MIN_VALUE ? new int[]{Math.min(a, b), Math.max(a, b)} : null;
        }
        int v = parseInt(unbracket(value), Integer.MIN_VALUE);
        return v != Integer.MIN_VALUE ? new int[]{v, v} : null;
    }

    private static String unbracket(String value) {
        return value.startsWith("(") && value.endsWith(")") ? value.substring(1, value.length() - 1) : value;
    }
}
