package dev.dusk.client.mixin.legacy;

import dev.dusk.client.compat.LegacyBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Legacy Combat: your own arm in third person, using vanilla's block pose,
 * which is close to 1.8's. Other players' blocks aren't sent on modern
 * servers, so only you are posed.
 */
@Mixin(AvatarRenderer.class)
public class LegacyArmPoseMixin {
    @Inject(method = "getArmPose(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;",
            at = @At("HEAD"), cancellable = true)
    private static void duskclient$blockingArm(Avatar avatar, ItemStack stack, InteractionHand hand,
                                               CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
        if (hand == InteractionHand.MAIN_HAND && !stack.isEmpty() && LegacyBlock.blocking()
                && avatar == Minecraft.getInstance().player) {
            cir.setReturnValue(HumanoidModel.ArmPose.BLOCK);
        }
    }
}
