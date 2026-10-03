package dev.dusk.client.module.setting;

/** A short line of free text, edited in place. */
public class TextSetting extends Setting<String> {
    private final int maxLength;

    public TextSetting(String id, String name, String defaultValue, int maxLength) {
        super(id, name, defaultValue);
        this.maxLength = maxLength;
    }

    public int maxLength() { return maxLength; }

    @Override
    public void set(String value) {
        super.set(value.length() > maxLength ? value.substring(0, maxLength) : value);
    }

    @Override
    public Object save() { return value; }

    @Override
    public void load(Object raw) {
        if (raw instanceof String s) set(s);
    }
}
