package dev.dusk.client.gui;

import dev.dusk.client.compat.Compat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.resources.language.I18n;

import java.util.Locale;

/**
 * The search box and filter the Controls → Key Binds screen gets (the mixins
 * add the widgets; this decides which rows stay). Text matches a key's name
 * or its category, every word has to appear; "By key" matches what it is
 * bound to instead.
 */
public final class KeybindSearch {
    private KeybindSearch() {}

    public enum Mode {
        ALL("All keys"), BY_KEY("By key"), CONFLICTS("Conflicts"), UNBOUND("Unbound");

        public final String label;

        Mode(String label) { this.label = label; }

        public Mode next() { return values()[(ordinal() + 1) % values().length]; }
    }

    /** What the search box and filter button remember while the screen rebuilds. */
    public static String query = "";
    public static Mode mode = Mode.ALL;

    /** Implemented on KeyBindsList by the mixin. */
    public interface Filterable {
        void dusk$filter(String query, Mode mode);
    }

    public static boolean matches(KeyMapping key, String query, Mode mode, KeyMapping[] all) {
        if (mode == Mode.CONFLICTS && !conflicts(key, all)) return false;
        if (mode == Mode.UNBOUND && !key.isUnbound()) return false;
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        if (mode == Mode.BY_KEY) {
            String bound = key.getTranslatedKeyMessage().getString().toLowerCase(Locale.ROOT);
            // "r" should find keys bound to R, not every bind with an r in "Right Button"
            return q.length() == 1 ? bound.equals(q) : bound.contains(q);
        }
        String hay = (I18n.get(key.getName()) + " " + Compat.keyCategoryLabel(key)).toLowerCase(Locale.ROOT);
        for (String word : q.split("\\s+")) {
            if (!hay.contains(word)) return false;
        }
        return true;
    }

    /** Bound to the same key as another mapping. */
    public static boolean conflicts(KeyMapping key, KeyMapping[] all) {
        if (key.isUnbound()) return false;
        for (KeyMapping other : all) {
            if (other != key && key.same(other)) return true;
        }
        return false;
    }
}
