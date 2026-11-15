package dev.dusk.client.module.setting;

import java.util.function.BooleanSupplier;

/**
 * One configurable value on a module. Same ids and saved form as the modern
 * Dusk build, so a config copied from 1.21.11 loads here.
 */
public abstract class Setting<T> {
    private final String id;
    private final String name;
    private final T defaultValue;
    protected T value;
    private String group;
    private BooleanSupplier visibleWhen;
    private Runnable onChange;

    protected Setting(String id, String name, T defaultValue) {
        this.id = id;
        this.name = name;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public String id() { return id; }
    public String name() { return name; }
    public T get() { return value; }
    public String group() { return group; }
    public void setGroup(String group) { this.group = group; }

    public Setting<T> visibleWhen(BooleanSupplier when) {
        this.visibleWhen = when;
        return this;
    }

    public boolean visible() { return visibleWhen == null || visibleWhen.getAsBoolean(); }

    public void onChange(Runnable onChange) { this.onChange = onChange; }

    public T defaultValue() { return defaultValue; }

    public void set(T value) {
        T before = this.value;
        this.value = value;
        if (onChange != null && (before == null ? value != null : !before.equals(value))) onChange.run();
    }

    public void reset() { this.value = defaultValue; }

    public abstract Object save();

    public abstract void load(Object raw);
}
