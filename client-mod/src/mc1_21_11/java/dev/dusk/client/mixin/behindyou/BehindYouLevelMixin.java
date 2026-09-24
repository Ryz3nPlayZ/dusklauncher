package dev.dusk.client.mixin.behindyou;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.render.BehindYou;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** BehindYou's eased third-person distance. */
@Mixin(Camera.class)
public class BehindYouLevelMixin {
    @WrapOperation(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
    private float dusk$behindYouLevel(Camera camera, float zoom, Operation<Float> original) {
        float maxZoom = original.call(camera, zoom);
        BehindYou m = BehindYou.active();
        return m != null ? m.level(maxZoom) : maxZoom;
    }
}
