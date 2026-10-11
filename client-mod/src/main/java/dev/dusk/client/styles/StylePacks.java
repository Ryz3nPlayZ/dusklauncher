package dev.dusk.client.styles;

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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
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

    /** One pack in the styles folder. {@code file} is its name in the folder. */
    public record Style(String file, String name, String category, boolean addon, @Nullable byte[] icon) {}

    public static final String OTHER = "other";
    public static final List<Category> CATEGORIES = List.of(
            new Category("crystal", "Crystal", Items.END_CRYSTAL),
            new Category("anchor", "Anchor", Items.RESPAWN_ANCHOR),
            new Category("totem", "Totem", Items.TOTEM_OF_UNDYING),
            new Category("shield", "Shield", Items.SHIELD),
            new Category("armor", "Armour", Items.DIAMOND_CHESTPLATE),
            new Category("sword", "Sword", Items.DIAMOND_SWORD),
            new Category("pickaxe", "Pickaxe", Items.DIAMOND_PICKAXE),
            new Category("fire", "Fire", Items.FLINT_AND_STEEL),
            new Category("glowstone", "Glowstone", Items.GLOWSTONE),
            new Category("deepslate", "Deepslate", Items.DEEPSLATE),
            new Category("snow", "Snow", Items.SNOW_BLOCK),
            new Category("grass", "Grass", Items.GRASS_BLOCK),
            new Category("stone", "Stone", Items.STONE),
            new Category("inventory", "Inventory", Items.CHEST),
            new Category(OTHER, "Other", Items.PAINTING));

    // Checked in this order for each file under assets/minecraft/, first match wins
    // (deepslate ores are deepslate, not stone; snowy grass is snow, not grass).
    private static final String[][] RULES = {
            {"crystal", "entity/end_crystal/|item/end_crystal"},
            {"anchor", "respawn_anchor"},
            {"totem", "totem_of_undying"},
            {"shield", "entity/shield|item/shield"},
            {"armor", "entity/equipment/humanoid|models/armor/|item/\\w+_(helmet|chestplate|leggings|boots)\\b|empty_armor_slot"},
            {"sword", "item/\\w+_sword\\b"},
            {"pickaxe", "item/\\w+_pickaxe\\b"},
            {"fire", "block/(soul_)?fire_\\d"},
            {"glowstone", "block/glowstone"},
            {"deepslate", "deepslate"},
            {"snow", "block/(snow|powder_snow|grass_block_snow)\\b"},
            {"grass", "colormap/(grass|foliage)|block/(grass_block|short_grass|tall_grass)"},
            {"stone", "block/(stone|cobblestone|smooth_stone|stone_bricks)\\b|block/\\w+_ore\\b"},
            {"inventory", "textures/gui/"},
    };
    private static final Pattern[] PATTERNS = new Pattern[RULES.length];
    private static final String CRYSTAL_TEXTURE = "assets/minecraft/textures/entity/end_crystal/end_crystal.png";

    static {
        for (int i = 0; i < RULES.length; i++) PATTERNS[i] = Pattern.compile(RULES[i][1]);
    }

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
        Map<String, Integer> textures = new HashMap<>(), models = new HashMap<>();
        byte[] icon = null;
        try {
            if (Files.isDirectory(p)) {
                if (!Files.isRegularFile(p.resolve("pack.mcmeta"))) return null;
                Path assets = p.resolve("assets/minecraft");
                if (Files.isDirectory(assets)) {
                    try (Stream<Path> walk = Files.walk(assets)) {
                        for (Path f : walk.filter(Files::isRegularFile).toList()) {
                            count("assets/minecraft/" + assets.relativize(f).toString().replace('\\', '/'), textures, models);
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
                        if (!e.isDirectory()) count(e.getName(), textures, models);
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
        String best = OTHER;
        int bestN = 0;
        for (String[] rule : RULES) {
            int n = textures.getOrDefault(rule[0], 0) + models.getOrDefault(rule[0], 0);
            if (n > bestN) {
                best = rule[0];
                bestN = n;
            }
        }
        boolean addon = bestN > 0 && textures.getOrDefault(best, 0) == 0;
        return new Style(file, prettyName(file, best), best, addon, icon);
    }

    private static void count(String path, Map<String, Integer> textures, Map<String, Integer> models) {
        if (!path.startsWith("assets/minecraft/")) return;
        String rel = path.substring("assets/minecraft/".length());
        boolean texture = rel.startsWith("textures/"), model = rel.startsWith("models/");
        if (!texture && !model) return;
        if (!rel.endsWith(".png") && !rel.endsWith(".json")) return;
        for (int i = 0; i < RULES.length; i++) {
            if (PATTERNS[i].matcher(rel).find()) {
                (texture ? textures : models).merge(RULES[i][0], 1, Integer::sum);
                return;
            }
        }
    }

    /** "crystal_see_through.zip" in the crystal category reads "See Through". */
    static String prettyName(String file, String category) {
        String n = file.toLowerCase(Locale.ROOT).endsWith(".zip") ? file.substring(0, file.length() - 4) : file;
        String lower = n.toLowerCase(Locale.ROOT);
        for (String prefix : new String[] {category + "_", category + "-", category + " "}) {
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
        List<String> picks = m == null ? List.of() : m.pickedFiles();
        crystalSeeThrough = false;
        if (picks.isEmpty()) return selected;

        Path dir = folder();
        Map<String, Pack> byFile = new LinkedHashMap<>();
        new FolderRepositorySource(dir, PackType.CLIENT_RESOURCES, PackSource.DEFAULT, mc.directoryValidator())
                .loadPacks(p -> byFile.put(fileOf(p.getId()), p));
        List<PackResources> out = new ArrayList<>(selected);
        for (String file : picks) {
            Pack p = byFile.get(file);
            if (p == null) continue;
            out.addAll(PackCompat.open(p));
        }
        String crystal = m.pick("crystal");
        if (crystal != null) crystalSeeThrough = glassy(dir.resolve(crystal));
        return out;
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
