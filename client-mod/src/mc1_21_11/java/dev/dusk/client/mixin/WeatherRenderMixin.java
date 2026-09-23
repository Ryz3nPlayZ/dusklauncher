package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.render.WeatherChanger;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PolyWeather: how hard the rain and snow columns are drawn. The renderer
 * asks the level once per frame; answering with our own strength is what
 * makes the picked weather visible. 26.2 asks a ClientLevel instead, so that
 * target keeps its own copy of this file.
 */
@Mixin(WeatherEffectRenderer.class)
public class WeatherRenderMixin {
    @WrapOperation(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getRainLevel(F)F"))
    private float duskclient$precipitationStrength(Level level, float delta, Operation<Float> original) {
        if (WeatherChanger.active()) return WeatherChanger.precipitationStrength(delta);
        return original.call(level, delta);
    }
}
