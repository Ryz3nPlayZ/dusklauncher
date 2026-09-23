package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.dusk.client.modules.render.WeatherChanger;
import net.minecraft.world.attribute.WeatherAttributes;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PolyWeather: 1.21.11 routes sky colour, cloud colour and sky darkening
 * through the environment attribute system rather than reading the level
 * directly. Handing that system a view of the weather that reports our levels
 * is what keeps the world's colours in step with the weather being drawn.
 * Only the client's own level is wrapped; server logic keeps real weather.
 */
@Mixin(WeatherAttributes.WeatherAccess.class)
public interface WeatherAttributesMixin {
    @ModifyReturnValue(method = "from", at = @At("RETURN"))
    private static WeatherAttributes.WeatherAccess duskclient$clientWeather(
            WeatherAttributes.WeatherAccess original, Level level) {
        if (!level.isClientSide()) return original;
        return new WeatherAttributes.WeatherAccess() {
            @Override
            public float rainLevel() {
                if (!WeatherChanger.active()) return original.rainLevel();
                return WeatherChanger.precipitationStrength(1.0f);
            }

            @Override
            public float thunderLevel() {
                if (!WeatherChanger.active()) return original.thunderLevel();
                return WeatherChanger.stormStrength(1.0f);
            }
        };
    }
}
