package dev.dusk.client.mixin.fov;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.dusk.client.modules.render.FovChanger;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** FovChanger's FOV, as a factor on the dynamic FOV so zoom and Behind You still scale it. */
@Mixin(value = Camera.class, priority = 900)
public class FovChangerMixin {
    @ModifyExpressionValue(method = "calculateFov", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F", ordinal = 0))
    private float dusk$fovChanger(float original) {
        return FovChanger.apply(original);
    }
}
