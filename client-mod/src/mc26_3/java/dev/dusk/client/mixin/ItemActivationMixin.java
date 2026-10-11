package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.dusk.client.modules.render.TotemTweaks;
import net.minecraft.client.player.ItemActivation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link TotemTweaks}: the totem pop's length, set as the pop starts (26.3 moved it here). */
@Mixin(ItemActivation.class)
public class ItemActivationMixin {
    @ModifyExpressionValue(method = "activate", at = @At(value = "CONSTANT", args = "intValue=40"))
    private int duskclient$startPop(int vanilla) {
        return TotemTweaks.startPop();
    }
}
