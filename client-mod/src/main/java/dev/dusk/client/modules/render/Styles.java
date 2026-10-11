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

    public Styles() {
        super("styles", "Styles", Category.RENDER,
                "Pick a look for crystals, anchors, swords, fire and more from packs in the instance's styles folder. One per category, kept apart from your resource packs.");
        instance = this;
        setEnabled(true);
        seeThrough.onChange(StylePacks::apply);
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

    /** Auto, On, Off, round again; reloads like changing it in settings. */
    public void cycleSeeThrough() {
        seeThrough.next();
    }

    @Override
    protected void onEnable() {
        if (!picks.get().isEmpty()) StylePacks.apply();
    }

    @Override
    protected void onDisable() {
        if (!picks.get().isEmpty()) StylePacks.apply();
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

    /** Every picked file, lowest priority first: picks in category order, then add-ons. */
    public List<String> pickedFiles() {
        Map<String, String> m = parsePicks();
        List<String> out = new ArrayList<>();
        for (StylePacks.Category c : StylePacks.CATEGORIES) {
            String f = m.get(c.id());
            if (f != null && !out.contains(f)) out.add(f);
        }
        for (String a : parseAddons()) if (!out.contains(a)) out.add(a);
        return out;
    }
}
