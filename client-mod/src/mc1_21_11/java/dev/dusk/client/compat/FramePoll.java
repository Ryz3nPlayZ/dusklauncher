package dev.dusk.client.compat;

import dev.dusk.client.mixin.lag.RenderSystemPollAccessor;

/** Runs the game's own event poll now, so a late poll keeps its freeze watchdog and mods' hooks. */
public final class FramePoll {
    public static final boolean SUPPORTED = true;

    private FramePoll() {}

    public static void poll() {
        RenderSystemPollAccessor.duskclient$pollEvents();
    }
}
