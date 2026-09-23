package dev.dusk.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.dusk.client.modules.render.MotionBlur;
import dev.dusk.client.render.motionblur.MotionBlurPipeline;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the motion-blur pipeline this frame's camera basis and runs the
 * velocity passes around the entity render, exactly where natural-motionblur
 * puts them: the pre-entity pass smears the terrain before entities are
 * drawn on top of it (so they stay sharp), the post pass catches the rest.
 */
@Mixin(LevelRenderer.class)
public class MotionBlurLevelMixin {

    @Unique private final Matrix4f duskclient$prevModelView = new Matrix4f();
    @Unique private final Matrix4f duskclient$prevProjection = new Matrix4f();
    @Unique private final Matrix4f duskclient$scratchModelView = new Matrix4f();
    @Unique private final Matrix4f duskclient$scratchProjection = new Matrix4f();
    @Unique private double duskclient$prevCamX, duskclient$prevCamY, duskclient$prevCamZ;
    @Unique private boolean duskclient$previousFrameReady = false;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void duskclient$onRenderLevelHead(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
                                              boolean renderOutline, CameraRenderState cameraRenderState,
                                              Matrix4fc modelViewMatrix, GpuBufferSlice terrainFog,
                                              Vector4f fogColor, boolean shouldRenderSky,
                                              ChunkSectionsToRender chunkSectionsToRender, CallbackInfo ci) {
        MotionBlur config = MotionBlur.instance();
        boolean blurActive = config != null && config.active();

        double cx = cameraRenderState.pos.x();
        double cy = cameraRenderState.pos.y();
        double cz = cameraRenderState.pos.z();

        if (!blurActive) {
            MotionBlurPipeline.clearFrameAllocator();
            duskclient$remember(modelViewMatrix, cameraRenderState.projectionMatrix, cx, cy, cz);
            return;
        }

        MotionBlurPipeline.captureAllocator(resourceAllocator);
        MotionBlurPipeline.beginFrame();

        if (!config.usesVelocityBlur()) {
            duskclient$remember(modelViewMatrix, cameraRenderState.projectionMatrix, cx, cy, cz);
            return;
        }

        duskclient$scratchModelView.set(modelViewMatrix);
        duskclient$scratchProjection.set(cameraRenderState.projectionMatrix);

        if (!duskclient$previousFrameReady) {
            MotionBlurPipeline.setFrameMotionBlur(
                    duskclient$scratchModelView, duskclient$scratchModelView,
                    duskclient$scratchProjection, duskclient$scratchProjection,
                    0.0f, 0.0f, 0.0f);
            duskclient$remember(duskclient$scratchModelView, duskclient$scratchProjection, cx, cy, cz);
            return;
        }

        float dx = (float) (cx - duskclient$prevCamX);
        float dy = (float) (cy - duskclient$prevCamY);
        float dz = (float) (cz - duskclient$prevCamZ);

        MotionBlurPipeline.setFrameMotionBlur(
                duskclient$scratchModelView, duskclient$prevModelView,
                duskclient$scratchProjection, duskclient$prevProjection,
                dx, dy, dz);

        duskclient$remember(duskclient$scratchModelView, duskclient$scratchProjection, cx, cy, cz);
    }

    @Unique
    private void duskclient$remember(Matrix4fc modelViewMatrix, Matrix4fc projectionMatrix, double cx, double cy, double cz) {
        duskclient$prevModelView.set(modelViewMatrix);
        duskclient$prevProjection.set(projectionMatrix);
        duskclient$prevCamX = cx;
        duskclient$prevCamY = cy;
        duskclient$prevCamZ = cz;
        duskclient$previousFrameReady = true;
    }

    /** Pre-entity blur. */
    @Inject(method = "submitEntities", at = @At("HEAD"))
    private void duskclient$beforeSubmitEntities(PoseStack poseStack, LevelRenderState levelRenderState,
                                                 SubmitNodeCollector output, CallbackInfo ci) {
        MotionBlur config = MotionBlur.instance();
        if (config == null || !config.active() || !config.usesVelocityBlur()) return;

        if (duskclient$specialSingleBlur()) {
            MotionBlurPipeline.applyF5EntityRideBlur();
            return;
        }
        MotionBlurPipeline.applyPreEntityBlur();
    }

    /** Post-entity blur. */
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void duskclient$onRenderLevelTail(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
                                              boolean renderOutline, CameraRenderState cameraRenderState,
                                              Matrix4fc modelViewMatrix, GpuBufferSlice terrainFog,
                                              Vector4f fogColor, boolean shouldRenderSky,
                                              ChunkSectionsToRender chunkSectionsToRender, CallbackInfo ci) {
        MotionBlur config = MotionBlur.instance();
        if (config == null) return;
        boolean specialSingleBlur = duskclient$specialSingleBlur();

        switch (config.algorithm()) {
            case HYBRID_BLENDING, VELOCITY_BASED -> {
                if (!specialSingleBlur) MotionBlurPipeline.applyPostRenderVelocityOnly();
            }
            default -> {}
        }
    }

    /**
     * Third person and riding: the pre-entity pass would smear the player or
     * the mount along with the world, so those frames get one combined pass
     * instead.
     */
    @Unique
    private boolean duskclient$specialSingleBlur() {
        Minecraft client = Minecraft.getInstance();
        if (client.options.getCameraType() != CameraType.FIRST_PERSON) return true;
        return client.player != null && client.player.isPassenger();
    }
}
