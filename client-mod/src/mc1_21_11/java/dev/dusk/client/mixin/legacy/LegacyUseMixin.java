package dev.dusk.client.mixin.legacy;

import dev.dusk.client.compat.LegacyBlock;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Legacy Combat: opening a door or a chest with your sword out doesn't raise it. */
@Mixin(MultiPlayerGameMode.class)
public class LegacyUseMixin {
    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void duskclient$clickUsed(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                      CallbackInfoReturnable<InteractionResult> cir) {
        if (cir.getReturnValue() instanceof InteractionResult.Success) LegacyBlock.useConsumed();
    }
}
