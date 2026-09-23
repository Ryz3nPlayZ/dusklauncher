package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.render.WeatherChanger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PolyWeather: rain darkens the fog and pulls it in, and thunder darkens it
 * further. Both come off the level's own levels, so the picked weather has to
 * be substituted here as well for the distance to match the sky.
 */
@Mixin(AtmosphericFogEnvironment.class)
public class WeatherFogMixin {
    @WrapOperation(
            method = {"getBaseColor", "updateRainFogState"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private float duskclient$weatherFog(ClientLevel level, float delta, Operation<Float> original) {
        if (WeatherChanger.active()) return WeatherChanger.precipitationStrength(delta);
        return original.call(level, delta);
    }

    @WrapOperation(
            method = "getBaseColor",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getThunderLevel(F)F"))
    private float duskclient$thunderFog(ClientLevel level, float delta, Operation<Float> original) {
        if (WeatherChanger.active()) return WeatherChanger.stormStrength(delta);
        return original.call(level, delta);
    }
}
