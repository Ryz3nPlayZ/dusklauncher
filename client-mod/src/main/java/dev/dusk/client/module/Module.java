package dev.dusk.client.module;

import dev.dusk.client.module.setting.Setting;

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
    private boolean enabled;

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

    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public Category category() { return category; }
    public boolean enabled() { return enabled; }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (enabled) onEnable(); else onDisable();
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

    public List<Setting<?>> settings() {
        return Collections.unmodifiableList(settings);
    }

    protected void onEnable() {}
    protected void onDisable() {}

    /** Called every client tick while enabled. */
    public void tick() {}

    public Map<String, Object> saveState() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("x", x);
        m.put("y", y);
        if (!settings.isEmpty()) {
            Map<String, Object> s = new LinkedHashMap<>();
            for (Setting<?> setting : settings) s.put(setting.id(), setting.save());
            m.put("settings", s);
        }
        return m;
    }

    public void loadState(Map<String, Object> m) {
        setEnabled(Boolean.TRUE.equals(m.get("enabled")));
        if (m.get("x") instanceof Number n) this.x = n.intValue();
        if (m.get("y") instanceof Number n) this.y = n.intValue();
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
