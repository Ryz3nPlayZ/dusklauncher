package dev.dusk.client.mixin;

import dev.dusk.client.modules.render.Freelook;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.entity.player.EntityPlayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Freelook: the particle billboard axes come from the camera's angles, not
 * the player's, so particles keep facing the view while it orbits.
 */
@Mixin(ActiveRenderInfo.class)
public abstract class ActiveRenderInfoMixin {
    @Redirect(method = "updateRenderInfo", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/player/EntityPlayer;rotationYaw:F", opcode = Opcodes.GETFIELD), require = 0)
    private static float dusk$yaw(EntityPlayer player) {
        Freelook look = Freelook.looking();
        return look != null ? look.yaw() : player.rotationYaw;
    }

    @Redirect(method = "updateRenderInfo", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/player/EntityPlayer;rotationPitch:F", opcode = Opcodes.GETFIELD), require = 0)
    private static float dusk$pitch(EntityPlayer player) {
        Freelook look = Freelook.looking();
        return look != null ? look.pitch() : player.rotationPitch;
    }
}
