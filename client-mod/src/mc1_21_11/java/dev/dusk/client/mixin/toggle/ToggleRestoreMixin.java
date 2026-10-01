package dev.dusk.client.mixin.toggle;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.ToggleKeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Toggle Sprint's fix for MC-301281 (Toggle Toggle Sprint's
 * ToggleKeyMappingMixin): since 1.21.9 a toggled Sprint or Sneak bound to a
 * mouse button is let go when a menu closes; it is restored like a keyboard
 * key instead. Only Sprint and Sneak. 1.21.9+ only.
 */
@Mixin(ToggleKeyMapping.class)
public abstract class ToggleRestoreMixin {
    @ModifyExpressionValue(method = "shouldRestoreStateOnScreenClosed", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/InputConstants$Key;getType()Lcom/mojang/blaze3d/platform/InputConstants$Type;"))
    private InputConstants.Type dusk$restoreMouseToggles(InputConstants.Type original) {
        Options options = Minecraft.getInstance().options;
        Object self = this;
        return self == options.keySprint || self == options.keyShift ? InputConstants.Type.KEYSYM : original;
    }
}
