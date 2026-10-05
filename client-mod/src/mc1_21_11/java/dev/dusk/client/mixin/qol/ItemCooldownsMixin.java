package dev.dusk.client.mixin.qol;

import dev.dusk.client.modules.hud.Cooldowns;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link Cooldowns}: hands the HUD each cooldown as it starts and ends, on
 * the local player's own cooldown clock. The integrated server's copy for
 * the same player is left out.
 */
@Mixin(ItemCooldowns.class)
public class ItemCooldownsMixin {
    @Shadow private int tickCount;

    private boolean duskclient$mine() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && p.getCooldowns() == (Object) this;
    }

    @Inject(method = "addCooldown(Lnet/minecraft/resources/Identifier;I)V", at = @At("TAIL"))
    private void duskclient$started(Identifier group, int ticks, CallbackInfo ci) {
        if (duskclient$mine()) Cooldowns.started(this, group.toString(), tickCount, tickCount + ticks);
    }

    @Inject(method = "onCooldownEnded", at = @At("HEAD"))
    private void duskclient$ended(Identifier group, CallbackInfo ci) {
        if (duskclient$mine()) Cooldowns.ended(this, group.toString());
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void duskclient$ticked(CallbackInfo ci) {
        if (duskclient$mine()) Cooldowns.ticked(this, tickCount);
    }
}
