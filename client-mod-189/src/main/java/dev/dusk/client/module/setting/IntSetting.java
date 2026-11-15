package dev.dusk.client.module.setting;

import java.util.function.IntFunction;

/** A whole number on a slider; {@code decimals} shows it as a fixed-point value (25 with 1 decimal is 2.5). */
public class IntSetting extends Setting<Integer> {
    private final int min, max, step;
    private final String suffix;
    private int decimals;
    private IntFunction<String> format;

    public IntSetting(String id, String name, int defaultValue, int min, int max, int step, String suffix) {
        super(id, name, defaultValue);
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
        this.suffix = suffix == null ? "" : suffix;
    }

    public IntSetting(String id, String name, int defaultValue, int min, int max) {
        this(id, name, defaultValue, min, max, 1, "");
    }

    public IntSetting decimals(int decimals) {
        this.decimals = decimals;
        return this;
    }

    public IntSetting format(IntFunction<String> format) {
        this.format = format;
        return this;
    }

    public int min() { return min; }
    public int max() { return max; }
    public int step() { return step; }
    public int decimals() { return decimals; }

    @Override
    public void set(Integer v) {
        int snapped = min + Math.round((v - min) / (float) step) * step;
        super.set(Math.max(min, Math.min(max, snapped)));
    }

    public float asFloat() {
        float f = value;
        for (int i = 0; i < decimals; i++) f /= 10f;
        return f;
    }

    public String display() {
        if (format != null) return format.apply(value);
        if (decimals == 0) return value + suffix;
        return dev.dusk.client.util.Fmt.fixed(asFloat(), decimals) + suffix;
    }

    public double fraction() { return max == min ? 0 : (value - min) / (double) (max - min); }

    public void setFraction(double f) {
        set((int) Math.round(min + Math.max(0, Math.min(1, f)) * (max - min)));
    }

    @Override
    public Object save() { return value; }

    @Override
    public void load(Object raw) {
        if (raw instanceof Number) {
            int v = (int) Math.round(((Number) raw).doubleValue());
            value = Math.max(min, Math.min(max, v));
        }
    }
}
