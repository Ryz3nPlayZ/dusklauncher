package dev.dusk.client.mixin.scale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.dusk.client.render.WorldScale;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** During Render Scale's world pass, the world (and every mod drawing into it) gets the smaller target. */
@Mixin(Minecraft.class)
public abstract class MainTargetMixin {
    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void duskclient$scaledTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget t = WorldScale.override();
        if (t != null) cir.setReturnValue(t);
    }
}
