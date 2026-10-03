package dev.dusk.client.mixin.freelook;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.render.Freecam;
import dev.dusk.client.modules.render.Freelook;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** While freelooking or in Freecam, the mouse turns the camera instead of the player. */
@Mixin(MouseHandler.class)
public class FreelookMouseMixin {
    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void dusk$freelookTurn(LocalPlayer player, double dx, double dy, Operation<Void> original) {
        Freecam f = Freecam.active();
        if (f != null) {
            f.turn(dx, dy);
            return;
        }
        Freelook m = Freelook.looking();
        if (m != null) m.turn(dx, dy);
        else original.call(player, dx, dy);
    }
}
