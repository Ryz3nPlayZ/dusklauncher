package dev.dusk.client.module;

import dev.dusk.client.module.setting.Setting;
import dev.dusk.client.module.setting.ToggleKeySetting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for every Dusk module: a HUD element (with a draggable position)
 * or a behaviour toggle. Ids, setting ids and saved form match the modern
 * build, so a config copied from 1.21.11 loads as is.
 */
public abstract class Module {
    private final String id;
    private final String name;
    private final String description;
    private final Category category;
    private final List<Setting<?>> settings = new ArrayList<Setting<?>>();
    private final ToggleKeySetting toggleKey = new ToggleKeySetting();
    private boolean enabled;
    private int x, y;

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
    /** False for modules whose switches live on a page of their own (Clips is on Media). */
    public boolean listed() { return true; }
    public ToggleKeySetting toggleKey() { return toggleKey; }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (enabled) onEnable(); else onDisable();
    }

    public void toggle() { setEnabled(!enabled); }

    public int x() { return x; }
    public int y() { return y; }
    public void setPosition(int x, int y) { this.x = x; this.y = y; }

    protected <S extends Setting<?>> S add(S setting) {
        settings.add(setting);
        return setting;
    }

    protected <S extends Setting<?>> S add(S setting, String group) {
        setting.setGroup(group);
        return add(setting);
    }

    protected void moveToFront(Setting<?>... first) {
        List<Setting<?>> moved = Arrays.asList(first);
        settings.removeAll(moved);
        settings.addAll(0, moved);
    }

    protected void moveToBack(Setting<?>... last) {
        List<Setting<?>> moved = Arrays.asList(last);
        settings.removeAll(moved);
        settings.addAll(moved);
    }

    public List<Setting<?>> settings() { return Collections.unmodifiableList(settings); }

    protected void onEnable() {}
    protected void onDisable() {}

    /** Every client tick while enabled. */
    public void tick() {}

    /** Ids this module was saved under before a rename. */
    public List<String> formerIds() { return Collections.emptyList(); }

    public Map<String, Object> saveState() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", enabled);
        m.put("x", x);
        m.put("y", y);
        Object key = toggleKey.save();
        if (key != null) m.put("toggleKey", key);
        if (!settings.isEmpty()) {
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            for (Setting<?> setting : settings) {
                Object saved = setting.save();
                if (saved != null) s.put(setting.id(), saved);
            }
            m.put("settings", s);
        }
        return m;
    }

    public void loadState(Map<?, ?> m) {
        setEnabled(Boolean.TRUE.equals(m.get("enabled")));
        if (m.get("x") instanceof Number) x = ((Number) m.get("x")).intValue();
        if (m.get("y") instanceof Number) y = ((Number) m.get("y")).intValue();
        toggleKey.load(m.get("toggleKey"));
        Object s = m.get("settings");
        if (s instanceof Map) {
            for (Setting<?> setting : settings) {
                Object raw = ((Map<?, ?>) s).get(setting.id());
                if (raw != null) setting.load(raw);
            }
        }
    }
}
