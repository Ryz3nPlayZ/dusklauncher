package dev.dusk.client.mixin.combo;

import dev.dusk.client.modules.hud.ComboDisplay;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Combo Counter: our swing, before the attack packet goes out. */
@Mixin(MultiPlayerGameMode.class)
public abstract class ComboAttackMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void dusk$comboAttack(Player player, Entity target, CallbackInfo ci) {
        ComboDisplay.onAttack(target, player);
    }
}
