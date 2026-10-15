package dev.dusk.client.styles;

import dev.dusk.client.DuskClient;
import dev.dusk.client.compat.PackCompat;
import dev.dusk.client.modules.render.Styles;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The resource packs in the instance's {@code styles} folder, sorted into
 * categories by the textures they change (a pack of sword textures is a
 * sword style whatever its name). One pack per category is picked on the
 * {@link dev.dusk.client.gui.StylesScreen}; the picks load on top of the
 * normal resource packs ({@code StylePacksMixin}) without showing in the
 * vanilla pack list. A pack that only changes models for a category (a glow
 * layer on the respawn anchor) is an add-on and stacks with that category's
 * pick. Bauloo Styles works the same way from the player's side, but turns
 * its packs on in the vanilla list instead.
 */
public final class StylePacks {
    public record Category(String id, String name, Item icon) {}

    /**
     * One pack in the styles folder. {@code file} is its name in the folder,
     * {@code category} what it is mostly for and {@code categories} every
     * category it can be picked for: a full PvP pack is a sword, a totem, a
     * sky and the rest, and picking it for one takes only that part.
     */
    public record Style(String file, String name, String category, List<String> categories, boolean addon, @Nullable byte[] icon) {
        public boolean in(String id) {
            return categories.contains(id);
        }
    }

    public static final String OTHER = "other";
    public static final List<Category> CATEGORIES = List.of(
            new Category("sky", "Sky", Items.SUNFLOWER),
            new Category("crystal", "Crystal", Items.END_CRYSTAL),
            new Category("anchor", "Anchor", Items.RESPAWN_ANCHOR),
            new Category("totem", "Totem", Items.TOTEM_OF_UNDYING),
            new Category("shield", "Shield", Items.SHIELD),
            new Category("armor", "Armour", Items.DIAMOND_CHESTPLATE),
            new Category("sword", "Sword", Items.DIAMOND_SWORD),
            new Category("axe", "Axe", Items.DIAMOND_AXE),
            new Category("pickaxe", "Pickaxe", Items.DIAMOND_PICKAXE),
            new Category("mace", "Mace", Items.MACE),
            new Category("trident", "Trident", Items.TRIDENT),
            new Category("pearl", "Ender Pearl", Items.ENDER_PEARL),
            new Category("gap", "Golden Apple", Items.GOLDEN_APPLE),
            new Category("obsidian", "Obsidian", Items.OBSIDIAN),
            new Category("cobweb", "Cobweb", Items.COBWEB),
            new Category("fire", "Fire", Items.FLINT_AND_STEEL),
            new Category("glowstone", "Glowstone", Items.GLOWSTONE),
            new Category("deepslate", "Deepslate", Items.DEEPSLATE),
            new Category("snow", "Snow", Items.SNOW_BLOCK),
            new Category("grass", "Grass", Items.GRASS_BLOCK),
            new Category("stone", "Stone", Items.STONE),
            new Category("hotbar", "Hotbar", Items.ITEM_FRAME),
            new Category("inventory", "Inventory", Items.CHEST),
            new Category(OTHER, "Other", Items.PAINTING));

    private static final String CRYSTAL_TEXTURE = "assets/minecraft/textures/entity/end_crystal/end_crystal.png";

    private static final Logger LOGGER = LoggerFactory.getLogger("duskclient-styles");

    private static boolean ready;
    private static volatile boolean crystalSeeThrough;

    private StylePacks() {}

    public static Path folder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("styles");
    }

    @Nullable
    public static Category category(String id) {
        for (Category c : CATEGORIES) if (c.id.equals(id)) return c;
        return null;
    }

    // ---- scanning ---------------------------------------------------------------

    /** Every pack in the styles folder, by name. Reads files; call it off the render thread. */
    public static List<Style> scan() {
        Path dir = folder();
        List<Style> out = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : files.sorted().toList()) {
                    Style s = read(p);
                    if (s != null) out.add(s);
                }
            }
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparing(Style::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    @Nullable
    private static Style read(Path p) {
        String file = p.getFileName().toString();
        if (file.startsWith(".")) return null;
        StyleFiles.Tally tally = new StyleFiles.Tally();
        byte[] icon = null;
        try {
            if (Files.isDirectory(p)) {
                if (!Files.isRegularFile(p.resolve("pack.mcmeta"))) return null;
                Path assets = p.resolve("assets/minecraft");
                if (Files.isDirectory(assets)) {
                    try (Stream<Path> walk = Files.walk(assets)) {
                        for (Path f : walk.filter(Files::isRegularFile).toList()) {
                            count("assets/minecraft/" + assets.relativize(f).toString().replace('\\', '/'), tally);
                        }
                    }
                }
                if (Files.isRegularFile(p.resolve("pack.png"))) icon = Files.readAllBytes(p.resolve("pack.png"));
            } else if (file.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                try (ZipFile zip = new ZipFile(p.toFile())) {
                    if (zip.getEntry("pack.mcmeta") == null) return null;
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        if (!e.isDirectory()) count(e.getName(), tally);
                    }
                    ZipEntry png = zip.getEntry("pack.png");
                    if (png != null) {
                        try (InputStream in = zip.getInputStream(png)) {
                            icon = in.readAllBytes();
                        }
                    }
                }
            } else {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        String best = tally.best();
        boolean addon = tally.addon();
        List<String> cats = addon || best.equals(OTHER) ? List.of(best) : tally.categories();
        return new Style(file, prettyName(file, cats.size() == 1 ? best : ""), best, cats, addon, icon);
    }

    /** Counts a file under today's name, so old packs sort like new ones. */
    private static void count(String path, StyleFiles.Tally tally) {
        if (path.startsWith(StyleFiles.MC)) tally.add(StyleFiles.names(path).get(0));
    }

    /** "crystal_see_through.zip" in the crystal category reads "See Through"; Minecraft's colour codes go. */
    static String prettyName(String file, String category) {
        String n = file.toLowerCase(Locale.ROOT).endsWith(".zip") ? file.substring(0, file.length() - 4) : file;
        n = n.replaceAll("(?i)\\.zip$", "").replaceAll("[\u00A7\uFFFD].?", "").replaceAll("^[!\\s]+", "");
        String lower = n.toLowerCase(Locale.ROOT);
        for (String prefix : category.isEmpty() ? new String[0] : new String[] {category + "_", category + "-", category + " "}) {
            if (lower.startsWith(prefix) && n.length() > prefix.length()) {
                n = n.substring(prefix.length());
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String word : n.replace('_', ' ').replace('-', ' ').trim().split(" +")) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.isEmpty() ? file : sb.toString();
    }

    // ---- applying ---------------------------------------------------------------

    /** Reloads resources so the current picks show; does nothing before the game's first load. */
    public static void apply() {
        Minecraft mc = Minecraft.getInstance();
        if (!ready || mc == null) return;
        mc.reloadResourcePacks();
    }

    /** Whether the picked crystal style's texture is see-through glass (for {@code CrystalSeeThroughMixin}). */
    public static boolean crystalSeeThrough() {
        Styles m = Styles.active();
        if (m == null) return false;
        return switch (m.seeThrough()) {
            case "On" -> true;
            case "Off" -> false;
            default -> crystalSeeThrough;
        };
    }

    /**
     * {@link PackRepository#openAllSelected} for the game's resources, with the
     * picked styles on top. Later packs win, so they go last.
     */
    public static List<PackResources> withStyles(PackRepository repo, List<PackResources> selected) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || repo != mc.getResourcePackRepository()) return selected;
        ready = true;
        Styles m = Styles.active();
        crystalSeeThrough = false;
        if (m == null) return selected;
        Path dir = folder();
        if (m.dropStale(file -> {
            Style s = read(dir.resolve(file));
            return s == null ? null : s.categories();
        })) {
            if (DuskClient.modules() != null) DuskClient.modules().saveConfig();
        }
        List<Map.Entry<String, String>> picks = m.picked();
        Map<String, String> looks = m.allLooks();
        if (picks.isEmpty() && looks.isEmpty()) {
            StyleFiles.prune(dir, Set.of());
            return selected;
        }

        // Each pick as the game should load it: a copy with old texture names fixed, other
        // categories' files left out and pack info every version takes (see StyleFiles)
        List<Path> load = new ArrayList<>();
        Path crystal = null;
        for (Map.Entry<String, String> pick : picks) {
            Path pack = dir.resolve(pick.getValue());
            if (!Files.exists(pack)) continue;
            try {
                Path copy = StyleFiles.prepare(pack, pick.getKey());
                load.add(copy);
                if ("crystal".equals(pick.getKey())) crystal = copy;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Couldn't load style {}: {}", pick.getValue(), e.toString());
            }
        }
        Set<Path> inUse = new HashSet<>(load);

        List<PackResources> out = new ArrayList<>(selected);
        Map<Path, Pack> byPath = packsIn(StyleFiles.cache(dir), mc);
        for (Path path : load) {
            Pack p = byPath.get(path);
            if (p != null) out.addAll(PackCompat.open(p));
            else LOGGER.warn("The game turned down style pack {}", path.getFileName());
        }

        // Looks go over everything: they recolour whatever ends up showing for their category
        List<Path> lookPacks = new ArrayList<>();
        for (Map.Entry<String, String> e : looks.entrySet()) {
            StyleLooks.Look look = StyleLooks.byId(e.getValue());
            if (look == null) continue;
            Path path = StyleFiles.cache(dir).resolve("look@" + e.getKey() + ".zip");
            try {
                if (StyleLooks.build(path, e.getKey(), look, out)) {
                    lookPacks.add(path);
                    inUse.add(path);
                }
            } catch (IOException | RuntimeException ex) {
                LOGGER.warn("Couldn't build the {} look for {}: {}", e.getValue(), e.getKey(), ex.toString());
            }
        }
        if (!lookPacks.isEmpty()) {
            byPath = packsIn(StyleFiles.cache(dir), mc);
            for (Path path : lookPacks) {
                Pack p = byPath.get(path);
                if (p != null) out.addAll(PackCompat.open(p));
            }
        }
        StyleFiles.prune(dir, inUse);
        if (crystal != null) crystalSeeThrough = glassy(crystal);
        return out;
    }

    /** The packs the game finds in {@code from}, by file. */
    private static Map<Path, Pack> packsIn(Path from, Minecraft mc) {
        Map<Path, Pack> byPath = new HashMap<>();
        if (!Files.isDirectory(from)) return byPath;
        new FolderRepositorySource(from, PackType.CLIENT_RESOURCES, PackSource.DEFAULT, mc.directoryValidator())
                .loadPacks(p -> byPath.put(from.resolve(fileOf(p.getId())), p));
        return byPath;
    }

    private static String fileOf(String packId) {
        int slash = packId.lastIndexOf('/');
        return slash >= 0 ? packId.substring(slash + 1) : packId;
    }

    /** True when most of the crystal texture is partly transparent (a glass crystal, not a cut-out one). */
    private static boolean glassy(Path pack) {
        try {
            BufferedImage img;
            if (Files.isDirectory(pack)) {
                Path f = pack.resolve(CRYSTAL_TEXTURE);
                if (!Files.isRegularFile(f)) return false;
                try (InputStream in = Files.newInputStream(f)) {
                    img = ImageIO.read(in);
                }
            } else {
                try (ZipFile zip = new ZipFile(pack.toFile())) {
                    ZipEntry e = zip.getEntry(CRYSTAL_TEXTURE);
                    if (e == null) return false;
                    try (InputStream in = zip.getInputStream(e)) {
                        img = ImageIO.read(in);
                    }
                }
            }
            if (img == null || !img.getColorModel().hasAlpha()) return false;
            int seen = 0, partial = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int a = img.getRGB(x, y) >>> 24;
                    if (a == 0) continue;
                    seen++;
                    if (a < 250) partial++;
                }
            }
            return seen > 0 && partial * 2 > seen;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
