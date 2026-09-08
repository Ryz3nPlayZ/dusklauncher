package dev.fasterlauncher.client.module;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for all FasterClient modules. A module is either a HUD element
 * (has a draggable anchor position) or a behavior toggle.
 */
public abstract class Module {
    private final String id;
    private final String name;
    private final Category category;
    private boolean enabled;

    /** HUD anchor, in scaled pixels, for Category.HUD modules. */
    private int x, y;

    public Module(String id, String name, Category category) {
        this.id = id;
        this.name = name;
        this.category = category;
    }

    public String id() { return id; }
    public String name() { return name; }
    public Category category() { return category; }
    public boolean enabled() { return enabled; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) onEnable(); else onDisable();
    }

    public int x() { return x; }
    public int y() { return y; }
    public void setPosition(int x, int y) { this.x = x; this.y = y; }

    protected void onEnable() {}
    protected void onDisable() {}

    /** Called every client tick while enabled. */
    public void tick() {}

    public Map<String, Object> saveState() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("x", x);
        m.put("y", y);
        return m;
    }

    public void loadState(Map<String, Object> m) {
        this.enabled = Boolean.TRUE.equals(m.get("enabled"));
        if (m.get("x") instanceof Number n) this.x = n.intValue();
        if (m.get("y") instanceof Number n) this.y = n.intValue();
    }

    public enum Category { HUD, MOVEMENT, RENDER, MISC }
}
