package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.dusk.client.modules.render.Fullbright;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Feeds the lightmap a larger gamma than the options slider allows while
 * the Fullbright module is on. Only the first OptionInstance.get() after
 * Options.gamma() is touched, so the other option reads stay vanilla.
 * 26.1: LightmapRenderStateExtractor.extract.
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class FullbrightMixin {
    @ModifyExpressionValue(
            method = "extract",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 0),
            slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;"))
    )
    private Object duskclient$gamma(Object original) {
        return Fullbright.applyGamma(original);
    }
}
