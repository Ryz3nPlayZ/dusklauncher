package dev.dusk.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.dusk.client.render.WorldProjection;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands this frame's camera matrices to {@link WorldProjection} for the HUD's world markers. */
@Mixin(LevelRenderer.class)
public class WorldProjectionMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void duskclient$captureCamera(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker, boolean renderOutline,
                                         CameraRenderState camera, Matrix4fc modelView, GpuBufferSlice terrainFog,
                                         Vector4f fogColor, boolean renderSky, ChunkSectionsToRender sections, CallbackInfo ci) {
        WorldProjection.capture(modelView, camera.projectionMatrix, camera.pos.x(), camera.pos.y(), camera.pos.z());
    }
}
