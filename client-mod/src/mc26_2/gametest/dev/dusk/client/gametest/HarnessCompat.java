package dev.dusk.client.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;

/** Fabric client-gametest API differences. 26.2 flavour (client-gametest-api 6.x). */
final class HarnessCompat {
    private HarnessCompat() {}

    static void waitForChunksRender(TestSingleplayerContext sp) {
        sp.getConnection().waitForChunksRender();
    }

    /** F1: hide the HUD for clean screenshots (Options.hideGui became Hud#toggle in 26.2). */
    static void hideHud(Minecraft mc) {
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
    }
}
