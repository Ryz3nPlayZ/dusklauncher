package dev.dusk.client.modules.render;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.compat.Input;
import dev.dusk.client.gui.StylesScreen;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.ActionSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.KeySetting;
import dev.dusk.client.module.setting.TextSetting;
import dev.dusk.client.styles.StylePacks;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Styles: swap crystals, anchors, swords, fire and the rest one at a time
 * from your own packs, without filling the resource pack list. Packs go in
 * the instance's {@code styles} folder; see {@link StylePacks}.
 */
public class Styles extends Module {
    private static Styles instance;
    /** Set once the see-through crystal hook is in (1.21.11; CrystalSeeThroughMixin). */
    public static volatile boolean crystalHooked;

    private final ActionSetting open = add(new ActionSetting("open", "Pick styles", "Open", () ->
            Compat.setScreen(Minecraft.getInstance(), new StylesScreen(Compat.currentScreen(Minecraft.getInstance())))));
    private final KeySetting key = add(new KeySetting("styles", "Open key", Input.UNKNOWN));
    private final ChoiceSetting seeThrough = add(new ChoiceSetting("seeThrough", "See-through crystals", "Auto", "Auto", "On", "Off"))
            .visibleWhen(() -> crystalHooked);
    // "category=file" per pick and "+=file" per add-on, one a line; edited on the Styles screen
    private final TextSetting picks = add(new TextSetting("picks", "Picks", "", 8000)).visibleWhen(() -> false);
    // "category=look" per recoloured category: a preset id or "#RRGGBB"
    private final TextSetting looks = add(new TextSetting("looks", "Looks", "", 4000)).visibleWhen(() -> false);
    // one saved setup a line: "name<TAB>picks<TAB>looks", the last two with their newlines as '|'
    private final TextSetting setups = add(new TextSetting("setups", "Setups", "", 32000)).visibleWhen(() -> false);

    public Styles() {
        super("styles", "Styles", Category.RENDER,
                "Pick a look for crystals, anchors, swords, fire and more from packs in the instance's styles folder. One per category, kept apart from your resource packs.");
        instance = this;
        setEnabled(true);
    }

    @Nullable
    public static Styles active() {
        Styles m = instance;
        return m != null && m.enabled() ? m : null;
    }

    @Nullable
    public static Styles get() {
        return instance;
    }

    public String seeThrough() {
        return seeThrough.get();
    }

    /** Auto, On, Off, round again. Takes effect on the next frame; no reload needed. */
    public void cycleSeeThrough() {
        seeThrough.next();
    }

    @Override
    protected void onEnable() {
        if (!picks.get().isEmpty() || !looks.get().isEmpty()) StylePacks.apply();
    }

    @Override
    protected void onDisable() {
        if (!picks.get().isEmpty() || !looks.get().isEmpty()) StylePacks.apply();
    }

    /** Polled every client tick, enabled or not, so the key works to open the screen. */
    public void tickKey() {
        Minecraft mc = Minecraft.getInstance();
        while (key.mapping().consumeClick()) {
            if (Compat.currentScreen(mc) == null) Compat.setScreen(mc, new StylesScreen(null));
        }
    }

    // ---- picks --------------------------------------------------------------------

    private Map<String, String> parsePicks() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : picks.get().split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && eq < line.length() - 1 && !line.startsWith("+")) m.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return m;
    }

    private Set<String> parseAddons() {
        Set<String> s = new LinkedHashSet<>();
        for (String line : picks.get().split("\n")) {
            if (line.startsWith("+=") && line.length() > 2) s.add(line.substring(2));
        }
        return s;
    }

    private void store(Map<String, String> pick, Set<String> addons) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : pick.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        for (String a : addons) sb.append("+=").append(a).append('\n');
        picks.set(sb.toString().strip());
    }

    /** The file picked for {@code category}, or null for the game's own look. */
    @Nullable
    public String pick(String category) {
        return parsePicks().get(category);
    }

    public boolean addonOn(String file) {
        return parseAddons().contains(file);
    }

    /** Picks {@code file} for {@code category} (null: back to the default look). */
    public void setPick(String category, @Nullable String file) {
        Map<String, String> m = parsePicks();
        if (file == null) m.remove(category); else m.put(category, file);
        store(m, parseAddons());
    }

    public void setAddon(String file, boolean on) {
        Set<String> s = parseAddons();
        if (on) s.add(file); else s.remove(file);
        store(parsePicks(), s);
    }

    /**
     * Every pick as category and file, lowest priority first: picks in
     * category order with Other first (it can carry anything, so the
     * category picks win over it), then add-ons with a null category (they
     * go with whatever category they are for).
     */
    public List<Map.Entry<String, String>> picked() {
        Map<String, String> m = parsePicks();
        List<Map.Entry<String, String>> out = new ArrayList<>();
        String other = m.get(StylePacks.OTHER);
        if (other != null) out.add(new java.util.AbstractMap.SimpleEntry<>(StylePacks.OTHER, other));
        for (StylePacks.Category c : StylePacks.CATEGORIES) {
            String f = m.get(c.id());
            if (f != null && !c.id().equals(StylePacks.OTHER)) out.add(new java.util.AbstractMap.SimpleEntry<>(c.id(), f));
        }
        for (String a : parseAddons()) out.add(new java.util.AbstractMap.SimpleEntry<>(null, a));
        return out;
    }

    /**
     * Drops picks a pack can't serve any more: a pack picked under a
     * category it has no textures for, or under Other once it's sorted into
     * a real category. Older versions sorted some packs differently (sky
     * packs went under Other before there was a Sky), and such a pick stayed
     * loaded, out of sight on the page, over whatever sky was picked.
     * {@code categories} gives a pack's categories (Other alone for a pack
     * of nothing in particular), or null when the pack can't be read.
     * Returns whether anything changed.
     */
    public boolean dropStale(java.util.function.Function<String, List<String>> categories) {
        Map<String, String> m = parsePicks();
        boolean changed = m.entrySet().removeIf(e -> {
            List<String> cats = categories.apply(e.getValue());
            return cats != null && !cats.contains(e.getKey());
        });
        if (changed) store(m, parseAddons());
        return changed;
    }

    /** What the page compares on close to know whether to reload. */
    public String signature() {
        return picks.get() + "\n#\n" + looks.get();
    }

    // ---- looks --------------------------------------------------------------------

    private Map<String, String> parseLooks() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : looks.get().split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && eq < line.length() - 1) m.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return m;
    }

    /** The look (a recolour id) for {@code category}, or null for its own colours. */
    @Nullable
    public String look(String category) {
        return parseLooks().get(category);
    }

    public void setLook(String category, @Nullable String id) {
        Map<String, String> m = parseLooks();
        if (id == null) m.remove(category); else m.put(category, id);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        looks.set(sb.toString().strip());
    }

    /** Every category with a look, in category order. */
    public Map<String, String> allLooks() {
        Map<String, String> m = parseLooks(), out = new LinkedHashMap<>();
        for (StylePacks.Category c : StylePacks.CATEGORIES) if (m.containsKey(c.id())) out.put(c.id(), m.get(c.id()));
        return out;
    }

    // ---- saved setups -------------------------------------------------------------

    private Map<String, String[]> parseSetups() {
        Map<String, String[]> m = new LinkedHashMap<>();
        for (String line : setups.get().split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length == 3 && !parts[0].isBlank()) m.put(parts[0], new String[] {parts[1].replace('|', '\n'), parts[2].replace('|', '\n')});
        }
        return m;
    }

    private void storeSetups(Map<String, String[]> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String[]> e : m.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue()[0].replace('\n', '|'))
                    .append('\t').append(e.getValue()[1].replace('\n', '|')).append('\n');
        }
        setups.set(sb.toString().strip());
    }

    /** The saved setups' names, oldest first. */
    public List<String> setupNames() {
        return new ArrayList<>(parseSetups().keySet());
    }

    /** Saves the current picks and looks under {@code name}, replacing a setup of that name. */
    public void saveSetup(String name) {
        String n = name.replace('\t', ' ').replace('\n', ' ').strip();
        if (n.isEmpty()) return;
        Map<String, String[]> m = parseSetups();
        m.remove(n);
        m.put(n, new String[] {picks.get(), looks.get()});
        storeSetups(m);
    }

    /** Puts a saved setup's picks and looks back. */
    public boolean loadSetup(String name) {
        String[] s = parseSetups().get(name);
        if (s == null) return false;
        picks.set(s[0]);
        looks.set(s[1]);
        return true;
    }

    public void deleteSetup(String name) {
        Map<String, String[]> m = parseSetups();
        if (m.remove(name) != null) storeSetups(m);
    }
}
