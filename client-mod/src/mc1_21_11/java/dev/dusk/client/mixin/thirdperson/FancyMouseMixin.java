package dev.dusk.client.mixin.thirdperson;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.render.thirdperson.FancyCamera;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Fancy Third Person: the mouse turns the camera; the player turns on its own. */
@Mixin(MouseHandler.class)
public class FancyMouseMixin {
    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void dusk$fancyTurn(LocalPlayer player, double dx, double dy, Operation<Void> original) {
        if (!FancyCamera.turn(dx, dy)) original.call(player, dx, dy);
    }
}
