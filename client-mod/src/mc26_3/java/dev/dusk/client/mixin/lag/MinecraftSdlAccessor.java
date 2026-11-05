package dev.dusk.client.mixin.lag;

import com.mojang.blaze3d.platform.SDLEventHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftSdlAccessor {
    @Accessor("sdlEventHandler")
    SDLEventHandler duskclient$sdlEvents();
}
