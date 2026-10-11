package dev.dusk.client.module.setting;

import java.util.Arrays;
import java.util.List;

/** One of a fixed list of strings; the editor shows it as a dropdown. */
public class ChoiceSetting extends Setting<String> {
    private final List<String> options;
    private java.util.function.UnaryOperator<String> label = o -> o;

    public ChoiceSetting(String id, String name, String defaultValue, String... options) {
        super(id, name, defaultValue);
        this.options = Arrays.asList(options);
    }

    public List<String> options() { return options; }

    /** Shows each option as {@code label} returns it (the saved value stays the plain option). */
    public ChoiceSetting labelled(java.util.function.UnaryOperator<String> label) {
        this.label = label;
        return this;
    }

    public String label(String option) { return label.apply(option); }

    public int index() { return Math.max(0, options.indexOf(get())); }

    public boolean is(String option) { return option.equals(get()); }

    public void next() { set(options.get((index() + 1) % options.size())); }

    @Override
    public Object save() { return value; }

    @Override
    public void load(Object raw) {
        if (raw instanceof String s && options.contains(s)) value = s;
    }
}
