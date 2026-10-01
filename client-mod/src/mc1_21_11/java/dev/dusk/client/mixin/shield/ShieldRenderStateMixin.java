package dev.dusk.client.mixin.shield;

import dev.dusk.client.render.shield.ShieldTint;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shield Statuses: carries a shield's tint from model resolution to its submit. */
@Mixin(ItemStackRenderState.class)
public abstract class ShieldRenderStateMixin implements ShieldTint.Holder {
    @Unique private int dusk$shieldTint = ShieldTint.NONE;

    @Override
    public void dusk$setShieldTint(int argb) {
        dusk$shieldTint = argb;
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void dusk$clearTint(CallbackInfo ci) {
        dusk$shieldTint = ShieldTint.NONE;
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private void dusk$publishTint(CallbackInfo ci) {
        ShieldTint.current = dusk$shieldTint;
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void dusk$retractTint(CallbackInfo ci) {
        ShieldTint.current = ShieldTint.NONE;
    }
}
