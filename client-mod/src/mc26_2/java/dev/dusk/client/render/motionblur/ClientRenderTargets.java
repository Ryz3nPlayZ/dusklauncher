package dev.dusk.client.render.motionblur;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;

/**
 * 26.2 dropped {@code Minecraft#getMainRenderTarget}; the game renderer owns
 * the main framebuffer now.
 */
public final class ClientRenderTargets {

    private ClientRenderTargets() {}

    public static RenderTarget getMain(Minecraft client) {
        return client.gameRenderer.mainRenderTarget();
    }
}
