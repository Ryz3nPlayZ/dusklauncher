package dev.dusk.client.module.setting;

/** ARGB colour, persisted as "#AARRGGBB" so the file stays hand-editable. */
public class ColorSetting extends Setting<Integer> {
    public ColorSetting(String id, String name, int argb) {
        super(id, name, argb);
    }

    public int argb() { return get(); }

    public String hex() { return String.format("%08X", get()); }

    /** Accepts RRGGBB or AARRGGBB, with or without '#'. */
    public boolean setHex(String hex) {
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() != 6 && h.length() != 8) return false;
        try {
            long v = Long.parseLong(h, 16);
            if (h.length() == 6) v |= 0xFF000000L;
            set((int) v);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public Object save() { return "#" + hex(); }

    @Override
    public void load(Object raw) {
        if (raw instanceof String s) setHex(s);
        else if (raw instanceof Number n) set(n.intValue());
    }
}
