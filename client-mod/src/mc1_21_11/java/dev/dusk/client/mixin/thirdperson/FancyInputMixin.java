package dev.dusk.client.mixin.thirdperson;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.render.thirdperson.FancyCamera;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Fancy Third Person: the movement keys walk relative to the camera, not the player. */
@Mixin(KeyboardInput.class)
public class FancyInputMixin {
    @WrapOperation(method = "tick", at = @At(value = "NEW", target = "(FF)Lnet/minecraft/world/phys/Vec2;"))
    private Vec2 dusk$fancyMove(float left, float forward, Operation<Vec2> original) {
        Vec2 v = FancyCamera.remapInput(left, forward);
        return original.call(v.x, v.y);
    }
}
