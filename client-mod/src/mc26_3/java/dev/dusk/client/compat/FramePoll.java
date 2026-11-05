package dev.dusk.client.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.dusk.client.mixin.lag.MinecraftSdlAccessor;
import net.minecraft.client.Minecraft;

/** Runs the game's own event poll now. 26.3 polls through its SDL event handler. */
public final class FramePoll {
    public static final boolean SUPPORTED = true;

    private FramePoll() {}

    public static void poll() {
        RenderSystem.pollEvents(((MinecraftSdlAccessor) Minecraft.getInstance()).duskclient$sdlEvents());
    }
}
