package dev.dusk.client.module.setting;

public class BoolSetting extends Setting<Boolean> {
    public BoolSetting(String id, String name, boolean defaultValue) {
        super(id, name, defaultValue);
    }

    public boolean on() { return value; }

    public void toggle() { set(!value); }

    @Override
    public Object save() { return value; }

    @Override
    public void load(Object raw) {
        if (raw instanceof Boolean) value = (Boolean) raw;
    }
}
