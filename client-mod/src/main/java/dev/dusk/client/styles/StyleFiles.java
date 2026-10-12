package dev.dusk.client.styles;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * What each file in a style pack is for, and the copy of a pack the game
 * actually loads. Packs made for older versions keep textures under old
 * names ({@code textures/items/}, {@code gold_sword}, {@code fire_layer_0},
 * armour layers under {@code textures/models/armor/}); the copy carries them
 * under today's names as well. The copy also leaves out files for other
 * categories, so a sword pick that happens to carry a GUI doesn't change the
 * GUI. Bauloo Styles does the same to its packs; this is written from how
 * that behaves, not from its code.
 */
final class StyleFiles {
    static final String MC = "assets/minecraft/";
    /** Bump when the renames or the filter change, so old copies get rebuilt. */
    private static final int VERSION = 1;

    // Checked in this order for each path under assets/minecraft/ (after renaming), first match
    // wins: deepslate ores are deepslate, not stone; snowy grass is snow, not grass; the hotbar
    // is its own category before the rest of the GUI.
    static final String[][] RULES = {
            {"sky", "^(optifine|mcpatcher)/sky/"},
            {"crystal", "entity/end_crystal/|item/end_crystal"},
            {"anchor", "respawn_anchor"},
            {"totem", "totem_of_undying"},
            {"shield", "entity/shield|item/shield|container/slot/shield"},
            {"armor", "entity/equipment/humanoid|models/armor/|item/\\w+_(helmet|chestplate|leggings|boots)\\b|empty_armor_slot|container/slot/(helmet|chestplate|leggings|boots)"},
            {"sword", "item/\\w+_sword\\b"},
            {"axe", "item/\\w+_axe\\b"},
            {"pickaxe", "item/\\w+_pickaxe\\b"},
            {"mace", "item/mace\\b"},
            {"trident", "entity/trident|item/trident"},
            {"pearl", "ender_pearl"},
            {"gap", "golden_apple"},
            {"obsidian", "block/(crying_)?obsidian"},
            {"cobweb", "block/cobweb\\b"},
            {"fire", "block/(soul_)?fire_\\d"},
            {"glowstone", "block/glowstone"},
            {"deepslate", "deepslate"},
            {"snow", "block/(snow|powder_snow|grass_block_snow)\\b"},
            {"grass", "colormap/(grass|foliage)|block/(grass_block|short_grass|tall_grass)"},
            {"stone", "block/(stone|cobblestone|smooth_stone|stone_bricks)\\b|block/\\w+_ore\\b"},
            {"hotbar", "gui/sprites/hud/hotbar"},
            {"inventory", "textures/gui/"},
    };
    private static final Pattern[] PATTERNS = new Pattern[RULES.length];

    static {
        for (int i = 0; i < RULES.length; i++) PATTERNS[i] = Pattern.compile(RULES[i][1]);
    }

    // Old name -> today's name, applied in order to paths under assets/minecraft/
    private static final String[][] RENAMES = {
            {"^textures/items/", "textures/item/"},
            {"^textures/blocks/", "textures/block/"},
            {"^textures/entity/endercrystal/endercrystal", "textures/entity/end_crystal/end_crystal"},
            {"^textures/block/fire_layer_([01])\\b", "textures/block/fire_$1"},
            {"^textures/block/grass_side_snowed\\b", "textures/block/grass_block_snow"},
            {"^textures/item/apple_golden\\b", "textures/item/golden_apple"},
            {"^textures/item/totem\\.png", "textures/item/totem_of_undying.png"},
            {"^textures/item/gold_(helmet|chestplate|leggings|boots|sword|pickaxe|axe|shovel|hoe)\\b", "textures/item/golden_$1"},
            {"^textures/item/wood_(sword|pickaxe|axe|shovel|hoe)\\b", "textures/item/wooden_$1"},
            {"^textures/entity/equipment/(humanoid|humanoid_leggings)/turtle(_overlay)?\\.png", "textures/entity/equipment/$1/turtle_scute$2.png"},
    };
    private static final Pattern[] RENAME_PATTERNS = new Pattern[RENAMES.length];

    static {
        for (int i = 0; i < RENAMES.length; i++) RENAME_PATTERNS[i] = Pattern.compile(RENAMES[i][0]);
    }

    // Moved rather than renamed: the old file stays for versions that still read it
    private static final Pattern OLD_ARMOR = Pattern.compile("^textures/models/armor/([a-z]+)_layer_([12])(_overlay)?\\.png$");
    private static final Pattern OLD_SLOT = Pattern.compile("^textures/item/empty_armor_slot_(helmet|chestplate|leggings|boots|shield)\\.png$");
    // Any quoted value in a model or item file that could name another model or texture
    private static final Pattern REF = Pattern.compile("\"(?:([a-z0-9_.-]+):)?([a-z0-9_./-]+)\"");

    private StyleFiles() {}

    /** The category a path under assets/minecraft/ belongs to, or null. Animation files go with their image. */
    @Nullable
    static String category(String rel) {
        if (rel.endsWith(".png.mcmeta")) rel = rel.substring(0, rel.length() - ".mcmeta".length());
        if (!rel.startsWith("textures/") && !rel.startsWith("models/") && !rel.startsWith("items/")
                && !rel.startsWith("optifine/") && !rel.startsWith("mcpatcher/")) return null;
        for (int i = 0; i < RULES.length; i++) if (PATTERNS[i].matcher(rel).find()) return RULES[i][0];
        return null;
    }

    /** Today's names for a file in a pack, the renamed path first. Only paths under assets/minecraft/ change. */
    static List<String> names(String path) {
        if (!path.startsWith(MC)) return List.of(path);
        String rel = path.substring(MC.length());
        String suffix = "";
        if (rel.endsWith(".png.mcmeta")) {
            suffix = ".mcmeta";
            rel = rel.substring(0, rel.length() - suffix.length());
        }
        for (int i = 0; i < RENAMES.length; i++) rel = RENAME_PATTERNS[i].matcher(rel).replaceFirst(RENAMES[i][1]);
        List<String> out = new ArrayList<>(2);
        out.add(MC + rel + suffix);
        Matcher m = OLD_ARMOR.matcher(rel);
        if (m.matches()) {
            String material = m.group(1).equals("turtle") ? "turtle_scute" : m.group(1);
            String layer = m.group(2).equals("1") ? "humanoid" : "humanoid_leggings";
            String overlay = m.group(3) == null ? "" : m.group(3);
            out.add(MC + "textures/entity/equipment/" + layer + "/" + material + overlay + ".png" + suffix);
        }
        m = OLD_SLOT.matcher(rel);
        if (m.matches()) out.add(MC + "textures/gui/sprites/container/slot/" + m.group(1) + ".png" + suffix);
        return out;
    }

    /** Counts a pack's files by category to find what the pack is. */
    static final class Tally {
        final Map<String, Integer> textures = new HashMap<>(), models = new HashMap<>();

        /** {@code path} is a full path in the pack, already renamed. */
        void add(String path) {
            if (!path.startsWith(MC)) return;
            String rel = path.substring(MC.length());
            if (!rel.endsWith(".png") && !rel.endsWith(".json") && !rel.endsWith(".properties")) return;
            String c = category(rel);
            if (c == null) return;
            // OptiFine skies (optifine/sky/world0/sky1.properties and its images) count as textures
            boolean model = rel.startsWith("models/") || rel.startsWith("items/");
            (model ? models : textures).merge(c, 1, Integer::sum);
        }

        /** The category with the most files, or {@link StylePacks#OTHER}. */
        String best() {
            String best = StylePacks.OTHER;
            int bestN = 0;
            for (String[] rule : RULES) {
                int n = textures.getOrDefault(rule[0], 0) + models.getOrDefault(rule[0], 0);
                if (n > bestN) {
                    best = rule[0];
                    bestN = n;
                }
            }
            return best;
        }

        /** Only models for its category: a glow layer that stacks on another pick. */
        boolean addon() {
            String b = best();
            return !b.equals(StylePacks.OTHER) && textures.getOrDefault(b, 0) == 0;
        }
    }

    // ---- the copy the game loads -------------------------------------------------

    /** A zip or folder pack, read by path inside the pack. */
    private interface Source extends AutoCloseable {
        List<String> paths();

        InputStream open(String path) throws IOException;

        @Override
        void close() throws IOException;
    }

    private static Source source(Path pack) throws IOException {
        if (Files.isDirectory(pack)) {
            List<String> paths = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(pack)) {
                for (Path f : walk.filter(Files::isRegularFile).toList()) paths.add(pack.relativize(f).toString().replace('\\', '/'));
            }
            return new Source() {
                public List<String> paths() { return paths; }
                public InputStream open(String path) throws IOException { return Files.newInputStream(pack.resolve(path)); }
                public void close() {}
            };
        }
        ZipFile zip = new ZipFile(pack.toFile());
        List<String> paths = new ArrayList<>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (!e.isDirectory()) paths.add(e.getName());
        }
        return new Source() {
            public List<String> paths() { return paths; }
            public InputStream open(String path) throws IOException { return zip.getInputStream(zip.getEntry(path)); }
            public void close() throws IOException { zip.close(); }
        };
    }

    /** Where the copy of {@code pack} goes. Its name is the pack's, so the game's pack id matches. */
    static Path copyOf(Path pack) {
        String name = pack.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) name += ".zip";
        return cache(pack.getParent()).resolve(name);
    }

    /** The copies live in a hidden folder inside the styles folder. */
    static Path cache(Path stylesDir) {
        return stylesDir.resolve(".dusk");
    }

    /**
     * The pack the game should load for {@code pack}: the pack itself when it
     * needs no changes, else a copy with old names added and other categories'
     * files left out, rebuilt only when the pack changes.
     */
    static Path prepare(Path pack) throws IOException {
        Path copy = copyOf(pack);
        String stamp = "dusk-style " + VERSION + " " + Files.size(pack) + " " + Files.getLastModifiedTime(pack).toMillis();
        if (Files.isDirectory(pack)) stamp = "dusk-style " + VERSION + " dir " + Files.getLastModifiedTime(pack).toMillis();
        if (Files.isRegularFile(copy) && stamp.equals(comment(copy))) return copy;

        try (Source src = source(pack)) {
            // Every file under its name(s) today. A file already under today's name beats an old one renamed to it.
            Map<String, String> files = new LinkedHashMap<>();
            List<String> paths = src.paths().stream().filter(p -> !junk(p)).toList();
            for (String p : paths) if (p.equals(names(p).get(0))) files.put(p, p);
            for (String p : paths) for (String n : names(p)) files.putIfAbsent(n, p);

            Tally tally = new Tally();
            files.keySet().forEach(tally::add);
            String category = tally.best();

            Set<String> keep = new HashSet<>();
            if (category.equals(StylePacks.OTHER)) {
                keep.addAll(files.keySet());
            } else {
                Deque<String> todo = new ArrayDeque<>();
                for (String p : files.keySet()) {
                    if (p.equals("pack.mcmeta") || p.equals("pack.png")
                            || p.startsWith(MC) && category.equals(category(p.substring(MC.length())))) {
                        keep.add(p);
                        if (p.endsWith(".json")) todo.add(p);
                    }
                }
                // Models and textures the kept models point at (a helper parent, a 3D model's own textures)
                while (!todo.isEmpty()) {
                    String json;
                    try (InputStream in = src.open(files.get(todo.pop()))) {
                        json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    Matcher m = REF.matcher(json);
                    while (m.find()) {
                        String ns = m.group(1) == null ? "minecraft" : m.group(1);
                        String base = "assets/" + ns + "/";
                        for (String ref : new String[] {base + "models/" + m.group(2) + ".json", base + "textures/" + m.group(2) + ".png"}) {
                            if (files.containsKey(ref) && keep.add(ref)) {
                                if (ref.endsWith(".json")) todo.add(ref);
                                else if (files.containsKey(ref + ".mcmeta")) keep.add(ref + ".mcmeta");
                            }
                        }
                    }
                }
            }

            boolean same = keep.size() == paths.size();
            for (String p : keep) same &= files.get(p).equals(p);
            if (same) {
                Files.deleteIfExists(copy);
                return pack;
            }

            Files.createDirectories(copy.getParent());
            Path tmp = Files.createTempFile(copy.getParent(), "copy-", ".tmp");
            try {
                try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
                    for (Map.Entry<String, String> e : files.entrySet()) {
                        if (!keep.contains(e.getKey())) continue;
                        out.putNextEntry(new ZipEntry(e.getKey()));
                        try (InputStream in = src.open(e.getValue())) {
                            in.transferTo(out);
                        }
                        out.closeEntry();
                    }
                    out.setComment(stamp);
                }
                Files.move(tmp, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return copy;
        }
    }

    /** Finder and Explorer leftovers. */
    private static boolean junk(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.equals(".ds_store") || name.equals("thumbs.db") || name.equals("desktop.ini") || path.startsWith("__MACOSX/");
    }

    @Nullable
    private static String comment(Path zip) {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            return z.getComment();
        } catch (IOException e) {
            return null;
        }
    }

    /** Removes copies of packs that aren't picked any more. */
    static void prune(Path dir, Set<Path> inUse) {
        Path cache = cache(dir);
        if (!Files.isDirectory(cache)) return;
        try (Stream<Path> files = Files.list(cache)) {
            for (Path f : files.toList()) if (!inUse.contains(f)) Files.deleteIfExists(f);
        } catch (IOException ignored) {
        }
    }
}
