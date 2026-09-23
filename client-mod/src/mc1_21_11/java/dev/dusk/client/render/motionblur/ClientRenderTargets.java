package dev.dusk.client.render.motionblur;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;

/**
 * Where the main framebuffer lives. 26.2 moved it off {@code Minecraft}, so
 * that game line ships its own copy of this class in its source layer.
 */
public final class ClientRenderTargets {

    private ClientRenderTargets() {}

    public static RenderTarget getMain(Minecraft client) {
        return client.getMainRenderTarget();
    }
}
