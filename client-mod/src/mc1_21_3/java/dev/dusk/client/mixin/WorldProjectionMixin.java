package dev.dusk.client.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.dusk.client.render.WorldProjection;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands this frame's camera matrices to {@link WorldProjection} for the HUD's world markers. */
@Mixin(LevelRenderer.class)
public class WorldProjectionMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void duskclient$captureCamera(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker, boolean renderOutline,
                                         Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
                                         Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
        var pos = camera.getPosition();
        WorldProjection.capture(modelView, projection, pos.x(), pos.y(), pos.z());
    }
}
