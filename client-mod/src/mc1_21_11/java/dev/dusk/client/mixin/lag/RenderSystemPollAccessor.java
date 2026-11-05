package dev.dusk.client.mixin.lag;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderSystem.class)
public interface RenderSystemPollAccessor {
    @Invoker("pollEvents")
    static void duskclient$pollEvents() {
        throw new AssertionError();
    }
}
