package dev.dusk.client.mixin.thirdperson;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.render.FancyThirdPerson;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Fancy Third Person: the crosshair still draws in the over-the-shoulder view. */
@Mixin(Hud.class)
public class FancyCrosshairMixin {
    @WrapOperation(method = "extractCrosshair", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/CameraType;isFirstPerson()Z"))
    private boolean dusk$fancyCrosshair(CameraType type, Operation<Boolean> original) {
        return original.call(type) || FancyThirdPerson.showCrosshair();
    }
}
