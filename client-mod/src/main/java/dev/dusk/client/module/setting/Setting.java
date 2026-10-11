package dev.dusk.client.module.setting;

/**
 * One configurable value on a module. Settings own their persistence
 * (JSON-friendly {@link #save()}/{@link #load(Object)}) and the editor
 * window picks a widget per subclass.
 */
public abstract class Setting<T> {
    private final String id;
    private final String name;
    private final T defaultValue;
    protected T value;
    private String group;
    private java.util.function.BooleanSupplier visibleWhen;
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

    /**
     * Optional section heading. Consecutive settings sharing a group render
     * as one collapsible block (OverflowParticles' per-particle entries).
     */
    public String group() { return group; }

    public void setGroup(String group) { this.group = group; }

    /** Hides the setting's row while {@code when} is false (options that don't apply right now). */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S visibleWhen(java.util.function.BooleanSupplier when) {
        this.visibleWhen = when;
        return (S) this;
    }

    public boolean visible() { return visibleWhen == null || visibleWhen.getAsBoolean(); }

    /** Runs whenever {@link #set} changes the value (not on load or reset). */
    public void onChange(Runnable onChange) { this.onChange = onChange; }

    public T defaultValue() { return defaultValue; }
    public void set(T value) {
        T before = this.value;
        this.value = value;
        if (onChange != null && !java.util.Objects.equals(before, value)) onChange.run();
    }
    public void reset() { this.value = defaultValue; }

    /** JSON-serialisable form (Gson primitives/strings). */
    public abstract Object save();

    /** Inverse of {@link #save()}; ignores values it cannot read. */
    public abstract void load(Object raw);
}
