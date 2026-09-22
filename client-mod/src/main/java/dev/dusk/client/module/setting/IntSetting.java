package dev.dusk.client.module.setting;

/** Bounded integer with a slider step and an optional unit suffix. */
public class IntSetting extends Setting<Integer> {
    private final int min, max, step;
    private final String suffix;

    public IntSetting(String id, String name, int defaultValue, int min, int max) {
        this(id, name, defaultValue, min, max, 1, "");
    }

    public IntSetting(String id, String name, int defaultValue, int min, int max, int step, String suffix) {
        super(id, name, defaultValue);
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
        this.suffix = suffix;
    }

    public int min() { return min; }
    public int max() { return max; }
    public int step() { return step; }
    public String suffix() { return suffix; }

    @Override
    public void set(Integer v) {
        int snapped = Math.round((v - min) / (float) step) * step + min;
        super.set(Math.max(min, Math.min(max, snapped)));
    }

    public void setFraction(double f) {
        set((int) Math.round(min + (max - min) * Math.max(0, Math.min(1, f))));
    }

    public double fraction() {
        return max == min ? 0 : (get() - min) / (double) (max - min);
    }

    @Override
    public Object save() { return value; }

    @Override
    public void load(Object raw) {
        if (raw instanceof Number n) set(n.intValue());
    }
}
