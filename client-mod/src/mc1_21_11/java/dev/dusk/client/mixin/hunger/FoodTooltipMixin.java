package dev.dusk.client.mixin.hunger;

import dev.dusk.client.gui.TooltipImage;
import dev.dusk.client.gui.TooltipImageComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hunger Info: AppleSkin's way into the tooltip, shared with Container
 * Preview. A {@link TooltipImage} goes in as a stand-in line of text, which
 * becomes its drawing here, where vanilla turns each line into what the
 * tooltip draws.
 */
@Mixin(ClientTooltipComponent.class)
public interface FoodTooltipMixin {
    @Inject(method = "create(Lnet/minecraft/util/FormattedCharSequence;)Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;",
            at = @At("HEAD"), cancellable = true)
    private static void dusk$foodTooltip(FormattedCharSequence text, CallbackInfoReturnable<ClientTooltipComponent> cir) {
        if (text instanceof TooltipImage.Line line) cir.setReturnValue(new TooltipImageComponent(line.image()));
    }
}
