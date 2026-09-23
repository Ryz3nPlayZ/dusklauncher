package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.render.WeatherChanger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PolyWeather's side of the weather that is not drawn: which precipitation a
 * block position counts as, the splash particles on the ground and how loud
 * the ambient loop is. 26.2 moved all three onto ClientLevel, where the
 * renderer now reads them from.
 */
@Mixin(ClientLevel.class)
public class WeatherTickMixin {
    @ModifyReturnValue(method = "getPrecipitationAt", at = @At("RETURN"))
    private Biome.Precipitation duskclient$precipitation(Biome.Precipitation original) {
        if (!WeatherChanger.active()) return original;
        if (WeatherChanger.isSnowy()) return Biome.Precipitation.SNOW;
        if (WeatherChanger.isRainy()) return Biome.Precipitation.RAIN;
        return Biome.Precipitation.NONE;
    }

    /** Snow draws its own particles, and clear weather should have none. */
    @Inject(method = "tickWeatherEffects", at = @At("HEAD"), cancellable = true)
    private void duskclient$cancelGroundParticles(CallbackInfo ci) {
        if (WeatherChanger.active() && (!WeatherChanger.isRainy() || WeatherChanger.isSnowy())) ci.cancel();
    }

    @WrapOperation(
            method = "tickWeatherEffects",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private float duskclient$soundStrength(ClientLevel level, float delta, Operation<Float> original) {
        if (WeatherChanger.active() && WeatherChanger.sounds()) return WeatherChanger.precipitationStrength(delta);
        return original.call(level, delta);
    }
}
