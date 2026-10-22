package dev.dusk.client.module;

import dev.dusk.client.module.setting.Setting;
import dev.dusk.client.module.setting.ToggleKeySetting;
import dev.dusk.client.perf.Perf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for all DuskClient modules. A module is either a HUD element
 * (has a draggable anchor position, see {@link dev.dusk.client.hud.HudElement})
 * or a behaviour toggle. Modules declare their options as {@link Setting}s
 * so the editor window and the config file need no per-module code.
 */
public abstract class Module {
    private final String id;
    private final String name;
    private final String description;
    private final Category category;
    private final List<Setting<?>> settings = new ArrayList<>();
    private final ToggleKeySetting toggleKey = new ToggleKeySetting();
    private boolean enabled;
    /** Switched off by the connected server (see ServerApi); the player's own choice is kept. */
    private boolean blocked;
    private Perf.Section perf;

    /** HUD anchor, in scaled pixels, for Category.HUD modules. */
    private int x, y;

    public Module(String id, String name, Category category) {
        this(id, name, category, "");
    }

    public Module(String id, String name, Category category, String description) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.description = description;
    }

    /** This module's row on the Performance page. */
    public Perf.Section perf() {
        if (perf == null) perf = Perf.section(name, category.label);
        return perf;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String description() { return blocked ? "Disabled by this server." : description; }
    public Category category() { return category; }
    public boolean enabled() { return enabled && !blocked; }
    public boolean blocked() { return blocked; }

    /** Switches the module on and off from in game; see ModuleManager#tickToggleKeys. */
    public ToggleKeySetting toggleKey() { return toggleKey; }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (blocked) return; // takes effect once the server lifts the block
        if (enabled) onEnable(); else onDisable();
    }

    /** Server-side override: the module stays off while blocked, whatever the player picks. */
    public void setBlocked(boolean blocked) {
        if (this.blocked == blocked) return;
        this.blocked = blocked;
        if (!enabled) return;
        if (blocked) onDisable(); else onEnable();
    }

    public int x() { return x; }
    public int y() { return y; }
    public void setPosition(int x, int y) { this.x = x; this.y = y; }

    protected <S extends Setting<?>> S add(S setting) {
        settings.add(setting);
        return setting;
    }

    /** Adds a setting under a collapsible section of the settings page. */
    protected <S extends Setting<?>> S add(S setting, String group) {
        setting.setGroup(group);
        return add(setting);
    }

    /** Moves these settings to the top of the settings page, in this order. */
    protected void moveToFront(Setting<?>... first) {
        List<Setting<?>> moved = List.of(first);
        settings.removeAll(moved);
        settings.addAll(0, moved);
    }

    /** Moves these settings to the bottom of the settings page, in this order. */
    protected void moveToBack(Setting<?>... last) {
        List<Setting<?>> moved = List.of(last);
        settings.removeAll(moved);
        settings.addAll(moved);
    }

    public List<Setting<?>> settings() {
        return Collections.unmodifiableList(settings);
    }

    protected void onEnable() {}
    protected void onDisable() {}

    /** Called every client tick while enabled. */
    public void tick() {}

    public Map<String, Object> saveState() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled); // the player's choice, not the server's block
        m.put("x", x);
        m.put("y", y);
        Object key = toggleKey.save();
        if (key != null) m.put("toggleKey", key);
        if (!settings.isEmpty()) {
            Map<String, Object> s = new LinkedHashMap<>();
            for (Setting<?> setting : settings) {
                Object saved = setting.save();
                if (saved != null) s.put(setting.id(), saved);
            }
            m.put("settings", s);
        }
        return m;
    }

    /**
     * Ids this module was saved under before it was renamed or merged into;
     * their saved state is read when the config has none under {@link #id()}.
     */
    public java.util.List<String> formerIds() {
        return java.util.List.of();
    }

    /**
     * Takes over one former module's saved state; returns whether that leaves
     * this module on. By default its settings carry over and it counts as on
     * if the old module was.
     */
    public boolean loadFormerState(String formerId, Map<String, Object> state) {
        loadState(state);
        return enabled;
    }

    public void loadState(Map<String, Object> m) {
        setEnabled(Boolean.TRUE.equals(m.get("enabled")));
        if (m.get("x") instanceof Number n) this.x = n.intValue();
        if (m.get("y") instanceof Number n) this.y = n.intValue();
        toggleKey.load(m.get("toggleKey"));
        if (m.get("settings") instanceof Map<?, ?> s) {
            for (Setting<?> setting : settings) {
                Object raw = s.get(setting.id());
                if (raw != null) setting.load(raw);
            }
        }
    }

    public enum Category {
        HUD("HUD"), MOVEMENT("Movement"), RENDER("Render"), MISC("Utility");

        public final String label;

        Category(String label) { this.label = label; }
    }
}
