package dev.dusk.client.mixin;

import dev.dusk.client.modules.misc.LowInputLag;
import dev.dusk.client.modules.render.Freecam;
import dev.dusk.client.modules.render.Freelook;
import dev.dusk.client.modules.render.Fullbright;
import dev.dusk.client.modules.render.Zoom;
import dev.dusk.client.render.MotionBlurRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The world renderer's Dusk hooks: gamma and the lightmap (Fullbright), mouse
 * deltas and view bobbing (Zoom), the GPU fence before the mouse read (Low
 * Input Lag), the motion blur velocity pre-pass, the Retina GUI mouse, and
 * Freelook/Freecam. Everything on this class is
 * {@code require = 0} because OptiFine patches around here too; a hook that
 * fails to apply just leaves vanilla behaviour.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "updateCameraAndRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/MouseHelper;mouseXYChange()V"), require = 0)
    private void dusk$beforeMouseRead(float partialTicks, long nanoTime, CallbackInfo ci) {
        LowInputLag.beforeMouseRead();
    }

    /** The world is drawn and the HUD isn't yet: where a video without the HUD is taken. */
    @Inject(method = "updateCameraAndRender", at = @At(value = "INVOKE_STRING",
            target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V", args = "ldc=gui"), require = 0)
    private void dusk$worldDone(float partialTicks, long nanoTime, CallbackInfo ci) {
        try {
            dev.dusk.client.media.ScreenRecorder.onFrame(false);
        } catch (Throwable t) {
            // a recorder fault must never cost a frame
        }
    }

    // A FIELD redirect replaces the read: the handler takes the owner (when it
    // isn't the target class itself) and returns the value — there is no
    // "original value" argument. The original is read from the owner here.
    @Redirect(method = "updateCameraAndRender", at = @At(value = "FIELD",
            target = "Lnet/minecraft/util/MouseHelper;deltaX:I", opcode = Opcodes.GETFIELD), require = 0)
    private int dusk$mouseX(net.minecraft.util.MouseHelper helper) {
        return Math.round(helper.deltaX * (float) Zoom.mouseFactor());
    }

    @Redirect(method = "updateCameraAndRender", at = @At(value = "FIELD",
            target = "Lnet/minecraft/util/MouseHelper;deltaY:I", opcode = Opcodes.GETFIELD), require = 0)
    private int dusk$mouseY(net.minecraft.util.MouseHelper helper) {
        return Math.round(helper.deltaY * (float) Zoom.mouseFactor());
    }

    @Redirect(method = "updateLightmap", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/settings/GameSettings;gammaSetting:F", opcode = Opcodes.GETFIELD), require = 0)
    private float dusk$gamma(net.minecraft.client.settings.GameSettings settings) {
        return Fullbright.gamma(settings.gammaSetting);
    }

    @Shadow @Final
    private int[] lightmapColors;

    /** Every lightmap upload, vanilla's and OptiFine's Custom Colors one: Fullbright's fallback boost. */
    @Inject(method = "updateLightmap", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/DynamicTexture;updateDynamicTexture()V"), require = 0)
    private void dusk$lightmapUpload(float partialTicks, CallbackInfo ci) {
        Fullbright.beforeLightmapUpload(lightmapColors);
    }

    /** Retina: the GUI mouse position handed to the open screen, in pixels like Minecraft's size. */
    @Redirect(method = "updateCameraAndRender", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/input/Mouse;getX()I"), require = 0)
    private int dusk$guiMouseX() {
        return dev.dusk.client.util.HiDpi.mouse(org.lwjgl.input.Mouse.getX());
    }

    @Redirect(method = "updateCameraAndRender", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/input/Mouse;getY()I"), require = 0)
    private int dusk$guiMouseY() {
        return dev.dusk.client.util.HiDpi.mouse(org.lwjgl.input.Mouse.getY());
    }

    /** The walk bob translate, shrunk by the zoom. */
    @Redirect(method = "setupViewBobbing", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GlStateManager;translate(FFF)V"), require = 0)
    private void dusk$bobTranslate(float x, float y, float z) {
        float k = Zoom.bobFactor();
        GlStateManager.translate(x * k, y * k, z * k);
    }

    /** The two bob rotations (not the third, which is the hurt camera). */
    @Redirect(method = "setupViewBobbing", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/renderer/GlStateManager;rotate(FFFF)V"), require = 0)
    private void dusk$bobRotate0(float angle, float x, float y, float z) {
        GlStateManager.rotate(angle * Zoom.bobFactor(), x, y, z);
    }

    @Redirect(method = "setupViewBobbing", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/client/renderer/GlStateManager;rotate(FFFF)V"), require = 0)
    private void dusk$bobRotate1(float angle, float x, float y, float z) {
        GlStateManager.rotate(angle * Zoom.bobFactor(), x, y, z);
    }

    /** The first entity pass, before the world is drawn: copy depth and colour for the velocity blur. */
    @Inject(method = "renderWorldPass", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/renderer/RenderGlobal;renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V"), require = 0)
    private void dusk$blurPrePass(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        MotionBlurRenderer.prePass(partialTicks);
    }

    /* ── Freelook / Freecam ── */

    /** The mouse turn (both the smooth-camera and the normal call): the camera's while one owns it. */
    @Redirect(method = "updateCameraAndRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/entity/EntityPlayerSP;setAngles(FF)V"), require = 0)
    private void dusk$turn(EntityPlayerSP player, float dYaw, float dPitch) {
        Freecam freecam = Freecam.active();
        if (freecam != null) { freecam.turn(dYaw, dPitch); return; }
        Freelook look = Freelook.looking();
        if (look != null) { look.turn(dYaw, dPitch); return; }
        player.setAngles(dYaw, dPitch);
    }

    /** Freelook's angles for the view entity, else the entity's own. */
    private static Freelook dusk$looking(Entity e) {
        Freelook look = Freelook.looking();
        return look != null && e == Minecraft.getMinecraft().getRenderViewEntity() ? look : null;
    }

    @Redirect(method = "orientCamera", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/Entity;rotationYaw:F", opcode = Opcodes.GETFIELD), require = 0)
    private float dusk$camYaw(Entity e) {
        Freelook look = dusk$looking(e);
        return look != null ? look.yaw() : e.rotationYaw;
    }

    @Redirect(method = "orientCamera", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/Entity;prevRotationYaw:F", opcode = Opcodes.GETFIELD), require = 0)
    private float dusk$camPrevYaw(Entity e) {
        Freelook look = dusk$looking(e);
        return look != null ? look.yaw() : e.prevRotationYaw;
    }

    @Redirect(method = "orientCamera", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/Entity;rotationPitch:F", opcode = Opcodes.GETFIELD), require = 0)
    private float dusk$camPitch(Entity e) {
        Freelook look = dusk$looking(e);
        return look != null ? look.pitch() : e.rotationPitch;
    }

    @Redirect(method = "orientCamera", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/Entity;prevRotationPitch:F", opcode = Opcodes.GETFIELD), require = 0)
    private float dusk$camPrevPitch(Entity e) {
        Freelook look = dusk$looking(e);
        return look != null ? look.pitch() : e.prevRotationPitch;
    }
}
