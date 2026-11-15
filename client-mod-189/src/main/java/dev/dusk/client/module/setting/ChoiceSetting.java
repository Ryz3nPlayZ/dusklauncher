package dev.dusk.client.module.setting;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/** One of a fixed list of options, saved by name. */
public class ChoiceSetting extends Setting<String> {
    private final List<String> options;
    private Function<String, String> labels;

    public ChoiceSetting(String id, String name, String defaultValue, String... options) {
        super(id, name, defaultValue);
        this.options = Collections.unmodifiableList(Arrays.asList(options));
    }

    public ChoiceSetting labelled(Function<String, String> labels) {
        this.labels = labels;
        return this;
    }

    public List<String> options() { return options; }

    public String label(String option) { return labels == null ? option : labels.apply(option); }

    public int index() { return Math.max(0, options.indexOf(value)); }

    public boolean is(String option) { return option.equals(value); }

    public void next(int dir) {
        int n = options.size();
        set(options.get(((index() + dir) % n + n) % n));
    }

    @Override
    public Object save() { return value; }

    @Override
    public void load(Object raw) {
        if (raw instanceof String && options.contains(raw)) value = (String) raw;
    }
}
