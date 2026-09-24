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

    public T defaultValue() { return defaultValue; }
    public void set(T value) { this.value = value; }
    public void reset() { this.value = defaultValue; }

    /** JSON-serialisable form (Gson primitives/strings). */
    public abstract Object save();

    /** Inverse of {@link #save()}; ignores values it cannot read. */
    public abstract void load(Object raw);
}
