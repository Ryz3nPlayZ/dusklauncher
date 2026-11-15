package dev.dusk.client.mixin;

import dev.dusk.client.modules.misc.BackgroundFps;
import dev.dusk.client.modules.misc.LowInputLag;
import dev.dusk.client.modules.render.Freecam;
import dev.dusk.client.util.HiDpi;
import dev.dusk.client.util.MacFullscreen;
import dev.dusk.client.util.PerfLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow private boolean fullscreen;
    @Shadow public int displayWidth;
    @Shadow public int displayHeight;
    @Shadow public GameSettings gameSettings;

    /*
     * macOS fullscreen: LWJGL 2 rebuilds the window to change fullscreen and
     * that crashes on current macOS, so F11 and the video setting use macOS's
     * own fullscreen instead (see MacFullscreen).
     */

    /** Start windowed; the end of startGame turns fullscreen on again through toggleFullscreen. */
    @Inject(method = "setInitialDisplayMode", at = @At("HEAD"), cancellable = true)
    private void dusk$macInitialWindow(CallbackInfo ci) throws Exception {
        if (!MacFullscreen.active() || !fullscreen) return;
        fullscreen = false;
        gameSettings.fullScreen = true;
        MacFullscreen.startWindowed(displayWidth, displayHeight);
        ci.cancel();
    }

    @Inject(method = "toggleFullscreen", at = @At("HEAD"), cancellable = true)
    private void dusk$macToggle(CallbackInfo ci) {
        if (!MacFullscreen.active()) return;
        boolean now = MacFullscreen.isFullscreen();
        if (!MacFullscreen.toggle()) return;
        fullscreen = !now;
        gameSettings.fullScreen = fullscreen;
        MacFullscreen.syncOptiFine(fullscreen);
        ci.cancel();
    }

    /** Vanilla ignores resizes while fullscreen; macOS fullscreen is just a bigger window, so keep them. */
    @Redirect(method = "checkWindowResize",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;fullscreen:Z", opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private boolean dusk$macResize(Minecraft self) {
        return !MacFullscreen.active() && fullscreen;
    }

    /** The green button changes fullscreen without F11; keep the setting in step with the window. */
    @Inject(method = "checkWindowResize", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;resize(II)V"))
    private void dusk$macSync(CallbackInfo ci) {
        if (!MacFullscreen.active()) return;
        fullscreen = MacFullscreen.isFullscreen();
        gameSettings.fullScreen = fullscreen;
        MacFullscreen.syncOptiFine(fullscreen);
    }

    /* Retina: a pixel-sized GL surface, with Minecraft's size in pixels (see HiDpi). */

    @Shadow public abstract void resize(int width, int height);

    @org.spongepowered.asm.mixin.Unique
    private int dusk$lastScale = 1;

    @Inject(method = "createDisplay", at = @At("HEAD"))
    private void dusk$hiDpiAsk(CallbackInfo ci) {
        HiDpi.beforeCreate();
    }

    /** The window was made in points; the framebuffer created next must be in pixels. */
    @Inject(method = "createDisplay", at = @At("RETURN"))
    private void dusk$hiDpiSize(CallbackInfo ci) {
        dusk$lastScale = HiDpi.scale();
        displayWidth = Math.max(1, HiDpi.width());
        displayHeight = Math.max(1, HiDpi.height());
    }

    @Redirect(method = "checkWindowResize", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;getWidth()I"))
    private int dusk$hiDpiWidth() {
        return HiDpi.width();
    }

    @Redirect(method = "checkWindowResize", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;getHeight()I"))
    private int dusk$hiDpiHeight() {
        return HiDpi.height();
    }

    /** Dragging the window between a Retina and a 1x screen changes the scale without a resize. */
    @Inject(method = "checkWindowResize", at = @At("HEAD"))
    private void dusk$hiDpiScreenChange(CallbackInfo ci) {
        int scale = HiDpi.scale();
        if (scale == dusk$lastScale) return;
        dusk$lastScale = scale;
        int w = Math.max(1, HiDpi.width()), h = Math.max(1, HiDpi.height());
        if (w != displayWidth || h != displayHeight) resize(w, h);
    }

    /** The frame cap, with the background module's smaller one applied. */
    @Inject(method = "getLimitFramerate", at = @At("RETURN"), cancellable = true)
    private void dusk$backgroundFps(CallbackInfoReturnable<Integer> cir) {
        int limited = BackgroundFps.limit(cir.getReturnValueI());
        if (limited != cir.getReturnValueI()) cir.setReturnValue(limited);
    }

    /** Every draw call for the frame is in: this is where its fence goes. */
    @Inject(method = "updateDisplay", at = @At("HEAD"))
    private void dusk$frameEnd(CallbackInfo ci) {
        LowInputLag.frameEnd();
        PerfLog.frameEnd();
        try {
            dev.dusk.client.media.ScreenRecorder.onFrame(true);
        } catch (Throwable t) {
            // a recorder fault must never cost a frame
        }
    }

    @Inject(method = "updateDisplay", at = @At("RETURN"))
    private void dusk$afterSwap(CallbackInfo ci) {
        PerfLog.afterSwap();
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    private void dusk$tickStart(CallbackInfo ci) {
        PerfLog.tickStart();
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void dusk$tickEnd(CallbackInfo ci) {
        PerfLog.tickEnd();
    }

    /**
     * Vanilla forces a full garbage collection on every world load, and a
     * lobby or game switch on a network like Hypixel loads two worlds in a
     * row: a freeze of a quarter second each time. The collector frees the
     * old world by itself soon enough.
     */
    @Redirect(method = "loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/System;gc()V"), require = 0)
    private void dusk$noForcedGc() {
        PerfLog.event("world load");
    }

    /* Freecam: the player stands still, so no attacking, using, mining or picking blocks. */

    @Inject(method = "clickMouse", at = @At("HEAD"), cancellable = true)
    private void dusk$freecamAttack(CallbackInfo ci) {
        if (Freecam.active() != null) ci.cancel();
    }

    @Inject(method = "rightClickMouse", at = @At("HEAD"), cancellable = true)
    private void dusk$freecamUse(CallbackInfo ci) {
        if (Freecam.active() != null) ci.cancel();
    }

    @Inject(method = "middleClickMouse", at = @At("HEAD"), cancellable = true)
    private void dusk$freecamPick(CallbackInfo ci) {
        if (Freecam.active() != null) ci.cancel();
    }

    @Inject(method = "sendClickBlockToController", at = @At("HEAD"), cancellable = true)
    private void dusk$freecamMine(boolean leftClick, CallbackInfo ci) {
        if (Freecam.active() != null) ci.cancel();
    }
}
