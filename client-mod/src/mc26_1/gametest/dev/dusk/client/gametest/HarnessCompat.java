package dev.dusk.client.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Fabric client-gametest API differences. 26.1 flavour (client-gametest-api 5.x). */
final class HarnessCompat {
    private HarnessCompat() {}

    static void waitForChunksRender(TestSingleplayerContext sp) {
        sp.getClientLevel().waitForChunksRender();
    }
}
