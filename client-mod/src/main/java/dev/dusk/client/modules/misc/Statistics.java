package dev.dusk.client.modules.misc;

import dev.dusk.client.module.Module;

/**
 * Swaps the vanilla Statistics screen for {@link dev.dusk.client.gui.DuskStatsScreen}:
 * searchable, sortable, grouped grids of items and mobs. Off brings vanilla's
 * screen back.
 */
public class Statistics extends Module {
    private static Statistics instance;

    public Statistics() {
        super("statistics", "Statistics", Category.MISC,
                "A better Statistics screen: search, sort and group items and mobs, with mobs drawn as models.");
        instance = this;
        setEnabled(true);
    }

    public static boolean on() {
        return instance != null && instance.enabled();
    }
}
