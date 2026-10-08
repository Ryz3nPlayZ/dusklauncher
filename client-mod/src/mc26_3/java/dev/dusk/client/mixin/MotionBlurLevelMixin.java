package dev.dusk.client.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.dusk.client.modules.render.MotionBlur;
import dev.dusk.client.render.motionblur.MotionBlurPipeline;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 version: {@code render} lost its model-view argument (the camera
 * state carries it), and terrain and solid features now share one render
 * pass, so there is no gap to blur the world before the entities go in.
 * Every frame gets the one combined pass third person uses on older
 * versions, after the level is drawn. Priority 800 so we sit in front of
 * shader mods.
 */
@Mixin(value = LevelRenderer.class, priority = 800)
public class MotionBlurLevelMixin {

    @Unique private final Matrix4f duskclient$prevModelView = new Matrix4f();
    @Unique private final Matrix4f duskclient$prevProjection = new Matrix4f();
    @Unique private final Matrix4f duskclient$scratchModelView = new Matrix4f();
    @Unique private final Matrix4f duskclient$scratchProjection = new Matrix4f();
    @Unique private double duskclient$prevCamX, duskclient$prevCamY, duskclient$prevCamZ;
    @Unique private boolean duskclient$previousFrameReady = false;
    @Unique private boolean duskclient$wasActive = false;

    @Inject(method = "render", at = @At("HEAD"))
    private void duskclient$onRenderHead(GraphicsResourceAllocator resourceAllocator, boolean renderOutline,
                                         CameraRenderState cameraRenderState, GpuBufferSlice terrainFog,
                                         Vector4f fogColor, boolean shouldRenderSky, boolean renderClouds,
                                         CallbackInfo ci) {
        Matrix4fc modelViewMatrix = cameraRenderState.viewRotationMatrix;
        MotionBlur config = MotionBlur.instance();
        boolean blurActive = config != null && config.active();

        // natural-motionblur resets its UBOs and frame history whenever the
        // blur is (re-)enabled, so stale matrices can never smear the first
        // frames back on.
        if (blurActive && !duskclient$wasActive) MotionBlurPipeline.invalidate();
        duskclient$wasActive = blurActive;

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

    /** The blur, once the whole level is drawn. */
    @Inject(method = "render", at = @At("TAIL"))
    private void duskclient$onRenderTail(GraphicsResourceAllocator resourceAllocator, boolean renderOutline,
                                         CameraRenderState cameraRenderState, GpuBufferSlice terrainFog,
                                         Vector4f fogColor, boolean shouldRenderSky, boolean renderClouds,
                                         CallbackInfo ci) {
        MotionBlur config = MotionBlur.instance();
        if (config == null) return;
        boolean specialSingleBlur = duskclient$specialSingleBlur();

        switch (config.algorithm()) {
            case HYBRID_BLENDING, VELOCITY_BASED -> {
                if (!config.active() || !config.usesVelocityBlur()) return;
                if (specialSingleBlur) MotionBlurPipeline.applyF5EntityRideBlur();
                else MotionBlurPipeline.applyPostRenderVelocityOnly();
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
