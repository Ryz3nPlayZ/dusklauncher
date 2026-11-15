package dev.dusk.client.module.setting;

import dev.dusk.client.util.Keys;

/** The key that turns a module on and off, saved by its modern name ("key.keyboard.v"); null when unbound. */
public class ToggleKeySetting extends Setting<Integer> {
    public ToggleKeySetting() {
        super("toggleKey", "Toggle key", 0);
    }

    public boolean bound() { return value != 0; }

    /** "None" when unbound, else the key as Controls names it. */
    public String keyName() { return bound() ? Keys.display(value) : "None"; }

    @Override
    public Object save() { return value == 0 ? null : Keys.name(value); }

    @Override
    public void load(Object raw) {
        value = raw instanceof String ? Keys.code((String) raw) : 0;
    }
}
