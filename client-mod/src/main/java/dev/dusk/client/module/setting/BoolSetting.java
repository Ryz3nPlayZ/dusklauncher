package dev.dusk.client.module.setting;

public class BoolSetting extends Setting<Boolean> {
    public BoolSetting(String id, String name, boolean defaultValue) {
        super(id, name, defaultValue);
    }

    public void toggle() { set(!get()); }

    @Override
    public Object save() { return value; }

    @Override
    public void load(Object raw) {
        if (raw instanceof Boolean b) value = b;
    }
}
