package dev.dusk.client.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Creates the test world with a cheap loading screen. Under CI's software GL
 * the blurred background and the chunk map ran the client at ~18 fps; the
 * gametest lockstep then kept the server behind schedule, and from 1.21.11 a
 * server that is behind skips chunk work, so spawn prep never got past 16%.
 */
final class Worlds {
    private Worlds() {}

    static TestSingleplayerContext create(ClientGameTestContext ctx) {
        ctx.runOnClient(client -> {
            client.options.menuBackgroundBlurriness().set(0);
            client.options.renderDistance().set(4);
        });
        return ctx.worldBuilder().create();
    }
}
