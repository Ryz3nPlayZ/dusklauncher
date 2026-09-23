package dev.dusk.client.mixin;

import dev.dusk.client.modules.toggle.ToggleSprint;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PolySprint's two client-side extras, both on the local player:
 *
 * <ul>
 *   <li>the double-tap-forward sprint timer is cleared every tick, so tapping
 *       W twice never starts a sprint behind your back;</li>
 *   <li>creative flight speed is multiplied, with sneak and jump given the
 *       same multiplier on the vertical axis.</li>
 * </ul>
 */
@Mixin(LocalPlayer.class)
public abstract class SprintMixin {
    @Shadow
    protected int sprintTriggerTime;

    @Inject(method = "tick", at = @At("HEAD"))
    private void duskclient$clearWTap(CallbackInfo ci) {
        if (ToggleSprint.disablesWTap()) this.sprintTriggerTime = 0;
    }

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void duskclient$flyBoost(CallbackInfo ci) {
        float boost = ToggleSprint.flyBoost();
        // With the boost off, flight speed is left to whoever else sets it.
        if (boost <= 0f) return;

        LocalPlayer player = (LocalPlayer) (Object) this;
        Abilities abilities = player.getAbilities();

        // Vanilla's creative flight speed, which the multiplier is applied to
        // and which the speed has to be put back to between boosts.
        final float base = 0.05f;

        if (!abilities.flying || !abilities.instabuild || !player.input.keyPresses.sprint()) {
            abilities.setFlyingSpeed(base);
            return;
        }

        abilities.setFlyingSpeed(base * boost);

        double vertical = 0.0;
        if (player.input.keyPresses.shift()) vertical -= 0.15 * boost;
        if (player.input.keyPresses.jump()) vertical += 0.15 * boost;
        if (vertical != 0.0) player.setDeltaMovement(player.getDeltaMovement().add(0.0, vertical, 0.0));
    }
}
