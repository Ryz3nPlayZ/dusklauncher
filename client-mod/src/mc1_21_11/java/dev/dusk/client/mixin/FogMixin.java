package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.dusk.client.modules.render.FogControl;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.minecraft.client.renderer.fog.environment.BlindnessFogEnvironment;
import net.minecraft.client.renderer.fog.environment.DarknessFogEnvironment;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import net.minecraft.client.renderer.fog.environment.LavaFogEnvironment;
import net.minecraft.client.renderer.fog.environment.PowderedSnowFogEnvironment;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * BactroMod's fog switches. The game picks the first applicable fog
 * environment and lets it fill in a FogData; we note which one ran and, once
 * the render-distance defaults are back in place, push every distance out to
 * infinity so nothing fades. Atmospheric fog keeps a sky and cloud limit, or
 * the horizon would render as a hard edge.
 */
@Mixin(value = FogRenderer.class, priority = 1500)
public class FogMixin {
    @Unique
    @Nullable
    private FogEnvironment duskclient$applied;

    @Inject(method = "setupFog", at = @At("HEAD"))
    private void duskclient$clearApplied(CallbackInfoReturnable<?> cir) {
        duskclient$applied = null;
    }

    @WrapOperation(
            method = "setupFog",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/fog/environment/FogEnvironment;setupFog(Lnet/minecraft/client/renderer/fog/FogData;Lnet/minecraft/client/Camera;Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/client/DeltaTracker;)V"))
    private void duskclient$noteApplied(FogEnvironment environment, FogData fog, Camera camera, ClientLevel level,
                                        float renderDistance, DeltaTracker deltaTracker, Operation<Void> original) {
        duskclient$applied = environment;
        original.call(environment, fog, camera, level, renderDistance, deltaTracker);
    }

    @Inject(
            method = "setupFog",
            at = @At(value = "FIELD",
                     target = "Lnet/minecraft/client/renderer/fog/FogData;renderDistanceEnd:F",
                     opcode = Opcodes.PUTFIELD,
                     ordinal = 0,
                     shift = At.Shift.AFTER))
    private void duskclient$hideFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker,
                                    float darkenWorldAmount, ClientLevel level, CallbackInfoReturnable<?> cir,
                                    @Local FogData fog) {
        FogControl.Fog kind = duskclient$kind(duskclient$applied);
        if (kind == null || !FogControl.hides(kind)) return;

        fog.environmentalStart = Float.MAX_VALUE;
        fog.environmentalEnd = Float.MAX_VALUE;
        fog.renderDistanceStart = Float.MAX_VALUE;
        fog.renderDistanceEnd = Float.MAX_VALUE;

        if (kind == FogControl.Fog.ATMOSPHERIC) {
            fog.skyEnd = Mth.clamp(renderDistanceInChunks * 16f, 2 * 16, 32 * 16);
            fog.cloudEnd = Minecraft.getInstance().options.cloudRange().get() * 16;
        } else {
            fog.skyEnd = Float.MAX_VALUE;
            fog.cloudEnd = Float.MAX_VALUE;
        }
    }

    @Unique
    @Nullable
    private static FogControl.Fog duskclient$kind(@Nullable FogEnvironment environment) {
        if (environment instanceof LavaFogEnvironment) return FogControl.Fog.LAVA;
        if (environment instanceof PowderedSnowFogEnvironment) return FogControl.Fog.POWDER_SNOW;
        if (environment instanceof BlindnessFogEnvironment) return FogControl.Fog.BLINDNESS;
        if (environment instanceof DarknessFogEnvironment) return FogControl.Fog.DARKNESS;
        if (environment instanceof WaterFogEnvironment) return FogControl.Fog.WATER;
        if (environment instanceof AtmosphericFogEnvironment) return FogControl.Fog.ATMOSPHERIC;
        return null;
    }
}
