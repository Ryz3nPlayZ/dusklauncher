package dev.dusk.client.mixin.shield;

import dev.dusk.client.render.shield.ShieldStateTracker;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Shield Tweaks' "Show others blocking properly" (Shield Fixes' LivingEntityMixin). */
@Mixin(LivingEntity.class)
public abstract class ShieldUseSyncMixin implements ShieldStateTracker.UseSync {
    @Shadow protected ItemStack useItem;
    @Shadow protected int useItemRemaining;

    @Override
    public void dusk$setUse(ItemStack stack, int remaining) {
        this.useItem = stack;
        this.useItemRemaining = remaining;
    }
}
