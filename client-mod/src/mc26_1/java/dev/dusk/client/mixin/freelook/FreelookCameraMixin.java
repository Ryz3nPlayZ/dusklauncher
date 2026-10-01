package dev.dusk.client.mixin.freelook;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.render.Freelook;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** While freelooking, the camera takes its rotation from Freelook instead of the player. */
@Mixin(Camera.class)
public class FreelookCameraMixin {
    @WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
    private float dusk$freelookYaw(Entity entity, float partialTick, Operation<Float> original) {
        Freelook m = Freelook.aim();
        return m != null && entity == Minecraft.getInstance().player ? m.yaw() : original.call(entity, partialTick);
    }

    @WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
    private float dusk$freelookPitch(Entity entity, float partialTick, Operation<Float> original) {
        Freelook m = Freelook.looking();
        return m != null && entity == Minecraft.getInstance().player ? m.pitch() : original.call(entity, partialTick);
    }
}
