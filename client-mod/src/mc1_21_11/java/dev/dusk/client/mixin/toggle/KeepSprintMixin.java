package dev.dusk.client.mixin.toggle;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import dev.dusk.client.modules.toggle.ToggleSprint;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ToggleKeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Toggle Sprint's "Keep sprinting after death" (Toggle Toggle Sprint's
 * KeyMappingMixin): respawning releases every toggled key except Sprint.
 */
@Mixin(KeyMapping.class)
public abstract class KeepSprintMixin {
    @WrapWithCondition(method = "resetToggleKeys",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ToggleKeyMapping;reset()V"))
    private static boolean dusk$keepSprint(ToggleKeyMapping key) {
        return key != Minecraft.getInstance().options.keySprint || !ToggleSprint.keepsSprintOnDeath();
    }
}
