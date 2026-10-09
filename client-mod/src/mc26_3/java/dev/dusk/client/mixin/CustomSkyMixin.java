package dev.dusk.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.dusk.client.render.sky.CustomSkyEngine;
import dev.dusk.client.render.sky.SkyGpu;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Custom skies: resource packs' OptiFine sky layers go after the sunrise glow
 * and before the sun, moon and stars, and over the End's sky box, inside the
 * sky's render pass. The sky renderer is rebuilt on every resource reload, so
 * that's when the packs are read again.
 */
@Mixin(SkyRenderer.class)
public class CustomSkyMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void duskclient$reloadSkies(CallbackInfo ci) {
        CustomSkyEngine.markDirty();
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void duskclient$extractSkies(ClientLevel level, float partialTick, Camera camera, SkyRenderState state, CallbackInfo ci) {
        CustomSkyEngine.extract(level, partialTick, state.sunAngle, state.rainBrightness);
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"))
    private void duskclient$drawSkies(RenderPass pass, PoseStack poseStack, float sunAngle, float moonAngle, float starAngle,
                                      MoonPhase moonPhase, float rainBrightness, float starBrightness, CallbackInfo ci) {
        if (!CustomSkyEngine.has(false)) return;
        Matrix4f base = new Matrix4f(RenderSystem.getModelViewStack()).mul(poseStack.last().pose())
                .rotate(Axis.YP.rotationDegrees(-90));
        CustomSkyEngine.draw(false, base, (texture, blend, mv, color) -> SkyGpu.draw(pass, texture, blend, mv, color));
    }

    @Inject(method = "renderEndSky", at = @At("TAIL"))
    private void duskclient$drawEndSkies(RenderPass pass, CallbackInfo ci) {
        if (!CustomSkyEngine.has(true)) return;
        CustomSkyEngine.draw(true, new Matrix4f(RenderSystem.getModelViewStack()),
                (texture, blend, mv, color) -> SkyGpu.draw(pass, texture, blend, mv, color));
    }
}
