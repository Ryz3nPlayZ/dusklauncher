package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.render.WeatherChanger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PolyWeather: rain fades the sun, moon and stars out. The sky renderer reads
 * the rain level for exactly that, so our weather has to reach it too, or a
 * picked storm would still have a clear starfield behind it.
 */
@Mixin(SkyRenderer.class)
public class WeatherSkyMixin {
    @WrapOperation(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private float duskclient$hideCelestialBodies(ClientLevel level, float delta, Operation<Float> original) {
        if (WeatherChanger.active()) return WeatherChanger.precipitationStrength(delta);
        return original.call(level, delta);
    }
}
