package dev.dusk.client.mixin.freecam;

import dev.dusk.client.modules.render.Freecam;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While Freecam is on, the camera sits where Freecam put it, detached so your player renders. */
@Mixin(Camera.class)
public abstract class FreecamCameraMixin {
    @Shadow private boolean detached;

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void dusk$freecam(float partialTick, CallbackInfo ci) {
        Freecam f = Freecam.active();
        if (f == null) return;
        detached = true;
        setRotation(f.yaw(), f.pitch());
        setPosition(f.x(partialTick), f.y(partialTick), f.z(partialTick));
    }
}
