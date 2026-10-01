package dev.dusk.client.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;

/** Fabric client-gametest API differences. 1.21.9–1.21.10 flavour. */
final class HarnessCompat {
    private HarnessCompat() {}

    static void waitForChunksRender(TestSingleplayerContext sp) {
        sp.getClientWorld().waitForChunksRender();
    }

    /** F1: hide the HUD for clean screenshots. */
    static void hideHud(Minecraft mc) {
        mc.options.hideGui = true;
    }
}
