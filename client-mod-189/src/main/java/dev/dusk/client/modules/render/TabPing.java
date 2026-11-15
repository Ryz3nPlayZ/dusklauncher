package dev.dusk.client.modules.render;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;

/**
 * Tab ping (5zig-style, behaviour): the tab list shows each player's ping as
 * a number instead of the bars. The column grows a little to fit it.
 */
public class TabPing extends Module {
    private static final int EXTRA_WIDTH = 12;

    private static TabPing instance;

    private final BoolSetting colored = add(new BoolSetting("colored", "Colour the number", true));

    public TabPing() {
        super("tabping", "Tab Ping", Category.RENDER,
                "Shows the ping of everyone in the tab list as a number.");
        instance = this;
    }

    public static boolean active() {
        TabPing m = instance;
        return m != null && m.enabled();
    }

    /** How much wider the tab list column is, for the number. */
    public static int extraWidth() {
        return active() ? EXTRA_WIDTH : 0;
    }

    /** The number shown for a latency in ms. */
    public static String text(int latency) {
        return latency < 0 ? "?" : String.valueOf(Math.min(9999, latency));
    }

    /** The colour for a latency in ms. */
    public static int color(int latency) {
        TabPing m = instance;
        if (m == null || !m.colored.get()) return 0xFFFFFFFF;
        if (latency < 0) return 0xFFAAAAAA;
        if (latency < 80) return 0xFF55FF55;
        if (latency < 150) return 0xFFC8E85A;
        if (latency < 300) return 0xFFFFAA00;
        return 0xFFFF5555;
    }
}
