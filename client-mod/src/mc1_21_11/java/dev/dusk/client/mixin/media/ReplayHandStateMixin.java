package dev.dusk.client.mixin.media;

import dev.dusk.client.media.impl.ReplayPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The first-person hand is drawn from the local player — in a replay, the
 * viewer. While the camera is in someone's eyes the viewer answers with
 * that player's hands: what they hold, their swing and what they're
 * using (eating, drawing a bow, blocking), so the hand on screen is theirs,
 * for the recorded player and everyone else alike.
 */
@Mixin(LivingEntity.class)
public abstract class ReplayHandStateMixin {
    @Inject(method = "getItemBySlot", at = @At("HEAD"), cancellable = true)
    private void duskclient$item(EquipmentSlot slot, CallbackInfoReturnable<ItemStack> cir) {
        if (slot.getType() != EquipmentSlot.Type.HAND) return;
        Player donor = ReplayPlayer.handDonor(this);
        if (donor != null) cir.setReturnValue(donor.getItemBySlot(slot));
    }

    @Inject(method = "getAttackAnim", at = @At("HEAD"), cancellable = true)
    private void duskclient$swing(float partial, CallbackInfoReturnable<Float> cir) {
        Player donor = ReplayPlayer.handDonor(this);
        if (donor != null) cir.setReturnValue(donor.getAttackAnim(partial));
    }

    @Inject(method = "isUsingItem", at = @At("HEAD"), cancellable = true)
    private void duskclient$using(CallbackInfoReturnable<Boolean> cir) {
        Player donor = ReplayPlayer.handDonor(this);
        if (donor != null) cir.setReturnValue(donor.isUsingItem());
    }

    @Inject(method = "getUsedItemHand", at = @At("HEAD"), cancellable = true)
    private void duskclient$usedHand(CallbackInfoReturnable<InteractionHand> cir) {
        Player donor = ReplayPlayer.handDonor(this);
        if (donor != null) cir.setReturnValue(donor.getUsedItemHand());
    }

    @Inject(method = "getUseItem", at = @At("HEAD"), cancellable = true)
    private void duskclient$useItem(CallbackInfoReturnable<ItemStack> cir) {
        Player donor = ReplayPlayer.handDonor(this);
        if (donor != null) cir.setReturnValue(donor.getUseItem());
    }

    @Inject(method = "getUseItemRemainingTicks", at = @At("HEAD"), cancellable = true)
    private void duskclient$useLeft(CallbackInfoReturnable<Integer> cir) {
        Player donor = ReplayPlayer.handDonor(this);
        if (donor != null) cir.setReturnValue(donor.getUseItemRemainingTicks());
    }

    @Inject(method = "getTicksUsingItem", at = @At("HEAD"), cancellable = true)
    private void duskclient$useTicks(CallbackInfoReturnable<Integer> cir) {
        Player donor = ReplayPlayer.handDonor(this);
        if (donor != null) cir.setReturnValue(donor.getTicksUsingItem());
    }
}
