package dev.dusk.client.mixin.thirdperson;

import dev.dusk.client.render.thirdperson.FancyCamera;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fancy Third Person places the back-view camera itself, after vanilla has set it up. */
@Mixin(Camera.class)
public abstract class FancyCameraMixin {
    @Shadow private Entity entity;
    @Shadow private float partialTickTime;
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("TAIL"))
    private void dusk$fancyThirdPerson(CallbackInfo ci) {
        if (!FancyCamera.place(entity, partialTickTime, Mth.lerp(partialTickTime, eyeHeightOld, eyeHeight))) return;
        setRotation(FancyCamera.yaw(), FancyCamera.pitch());
        setPosition(FancyCamera.x(), FancyCamera.y(), FancyCamera.z());
    }
}
