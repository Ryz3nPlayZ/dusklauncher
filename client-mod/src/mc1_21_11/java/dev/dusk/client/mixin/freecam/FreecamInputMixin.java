package dev.dusk.client.mixin.freecam;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.render.Freecam;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** While Freecam is on, the movement keys fly the camera instead of walking your player. */
@Mixin(KeyboardInput.class)
public class FreecamInputMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean dusk$freecamInput(KeyMapping key, Operation<Boolean> original) {
        return Freecam.active() == null && original.call(key);
    }
}
