package dev.dusk.client.module.setting;

/** An ARGB colour, saved as "#AARRGGBB". */
public class ColorSetting extends Setting<Integer> {
    private boolean opaque;

    public ColorSetting(String id, String name, int defaultArgb) {
        super(id, name, defaultArgb);
    }

    public ColorSetting opaque() {
        this.opaque = true;
        return this;
    }

    public boolean isOpaque() { return opaque; }

    public int argb() { return opaque ? value | 0xFF000000 : value; }

    public String hex() { return String.format("%08X", argb()); }

    /** RRGGBB or AARRGGBB, with or without '#'; false when unreadable. */
    public boolean setHex(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 6) {
                set(0xFF000000 | (int) Long.parseLong(s, 16));
                return true;
            }
            if (s.length() == 8) {
                set((int) Long.parseLong(s, 16));
                return true;
            }
        } catch (NumberFormatException ignored) {
        }
        return false;
    }

    @Override
    public Object save() { return "#" + hex(); }

    @Override
    public void load(Object raw) {
        if (raw instanceof String) {
            Integer before = value;
            if (!setHex((String) raw)) value = before;
        } else if (raw instanceof Number) {
            value = (int) ((Number) raw).longValue();
        }
    }
}
