package dev.dusk.client.module.setting;

/** Bounded integer with a slider step and an optional unit suffix. */
public class IntSetting extends Setting<Integer> {
    private final int min, max, step;
    private final String suffix;
    private int decimals;

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

    /** Shows the stored integer as a fixed-point number: 2 turns 125 into "1.25". */
    public IntSetting decimals(int decimals) {
        this.decimals = Math.max(0, decimals);
        return this;
    }

    /** The value as the settings UI prints it, including the suffix. */
    public String display() {
        int v = get();
        if (decimals == 0) return v + suffix;
        return java.math.BigDecimal.valueOf(v, decimals).stripTrailingZeros().toPlainString() + suffix;
    }

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
