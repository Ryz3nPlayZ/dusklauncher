package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;

/** The tab list shows each player's ping in milliseconds instead of signal bars. */
public class TabPing extends Module {
    /** Extra column width the numbers need over the 13 px the bars take. */
    public static final int EXTRA_WIDTH = 12;
    private static TabPing instance;

    private final BoolSetting colored = add(new BoolSetting("colored", "Colour by ping", true));

    public TabPing() {
        super("tabping", "Tab Ping", Category.RENDER,
                "Shows ping as a number in the tab list instead of bars.");
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.enabled();
    }

    /** The column the ping sits in, widened while the numbers are on. */
    public static int columnWidth(int vanilla) {
        return active() ? vanilla + EXTRA_WIDTH : vanilla;
    }

    public static String text(int latency) {
        return latency < 0 ? "?" : Integer.toString(Math.min(latency, 9999));
    }

    public static int color(int latency) {
        if (!instance.colored.get()) return 0xFFFFFFFF;
        if (latency < 0) return 0xFFAAAAAA;
        if (latency < 80) return 0xFF55FF55;
        if (latency < 150) return 0xFFC8E85A;
        if (latency < 300) return 0xFFFFAA00;
        return 0xFFFF5555;
    }
}
