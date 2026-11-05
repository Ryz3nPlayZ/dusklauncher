package dev.dusk.client.compat;

/** Low Input Lag starts at 1.21.11; below that the module isn't registered. */
public final class FramePoll {
    public static final boolean SUPPORTED = false;

    private FramePoll() {}

    public static void poll() {}
}
