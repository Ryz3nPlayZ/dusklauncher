package dev.dusk.client.mixin.behindyou;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.dusk.client.modules.render.BehindYou;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** BehindYou's per-view FOV, scaled onto the vanilla FOV so zoom effects still apply. */
@Mixin(Camera.class)
public class BehindYouFovMixin {
    @ModifyExpressionValue(method = "calculateFov", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F", ordinal = 0))
    private float dusk$behindYouFov(float original) {
        BehindYou m = BehindYou.active();
        if (m == null) return original;
        int fov = Minecraft.getInstance().options.fov().get();
        return original * m.fov(fov) / fov;
    }
}
