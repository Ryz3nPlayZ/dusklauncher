package dev.dusk.client.module.setting;

import java.util.Arrays;
import java.util.List;

/** One of a fixed list of strings; the editor shows it as a cycle button. */
public class ChoiceSetting extends Setting<String> {
    private final List<String> options;

    public ChoiceSetting(String id, String name, String defaultValue, String... options) {
        super(id, name, defaultValue);
        this.options = Arrays.asList(options);
    }

    public List<String> options() { return options; }

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
