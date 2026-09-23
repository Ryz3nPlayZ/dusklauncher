package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.dusk.client.modules.render.NoNightVision;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * BactroMod's "Night Vision" toggle. The lightmap asks how strong the effect
 * is right now; answering zero drops the brightness boost and the flicker
 * that comes with it. Named getNightVisionScale up to 26.1, nightVisionScale
 * from 26.2.
 */
@Mixin(GameRenderer.class)
public class NightVisionMixin {
    @ModifyReturnValue(method = {"getNightVisionScale", "nightVisionScale"}, at = @At("RETURN"), require = 1)
    private static float duskclient$noNightVision(float original) {
        return NoNightVision.active() ? 0f : original;
    }
}
