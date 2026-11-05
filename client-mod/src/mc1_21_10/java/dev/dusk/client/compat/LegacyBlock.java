package dev.dusk.client.compat;

/** Legacy Combat starts at 1.21.11; below that the module isn't registered. */
public final class LegacyBlock {
    public static final boolean SUPPORTED = false;

    private LegacyBlock() {}

    public static boolean blocking() {
        return false;
    }

    public static void tick() {}

    public static void reset() {}
}
