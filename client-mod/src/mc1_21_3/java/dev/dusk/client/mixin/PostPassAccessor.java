package dev.dusk.client.mixin;

import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** 1.21.3 flavour: uniforms are set per RenderPass, so only the inputs are needed. */
@Mixin(PostPass.class)
public interface PostPassAccessor {
    @Accessor("inputs")
    List<PostPass.Input> duskclient$inputs();
}
