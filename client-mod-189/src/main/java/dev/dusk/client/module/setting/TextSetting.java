package dev.dusk.client.module.setting;

public class TextSetting extends Setting<String> {
    private final int maxLength;

    public TextSetting(String id, String name, String defaultValue) {
        this(id, name, defaultValue, 256);
    }

    public TextSetting(String id, String name, String defaultValue, int maxLength) {
        super(id, name, defaultValue);
        this.maxLength = maxLength;
    }

    public int maxLength() { return maxLength; }

    @Override
    public Object save() { return value; }

    @Override
    public void load(Object raw) {
        if (raw instanceof String) value = (String) raw;
    }
}
