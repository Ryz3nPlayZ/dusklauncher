package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.render.WeatherChanger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PolyWeather: how hard the rain and snow columns are drawn. 26.2 narrowed
 * the renderer to a ClientLevel, so the call being wrapped is that one.
 */
@Mixin(WeatherEffectRenderer.class)
public class WeatherRenderMixin {
    @WrapOperation(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private float duskclient$precipitationStrength(ClientLevel level, float delta, Operation<Float> original) {
        if (WeatherChanger.active()) return WeatherChanger.precipitationStrength(delta);
        return original.call(level, delta);
    }
}
