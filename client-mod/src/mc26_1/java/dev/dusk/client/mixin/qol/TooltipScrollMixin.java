package dev.dusk.client.mixin.qol;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.misc.ScrollableTooltips;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link ScrollableTooltips} on 26.x: every tooltip is placed through its
 * positioner in GuiGraphicsExtractor.tooltip; the scroll moves what it
 * returns. The 1.21.x copy hooks GuiGraphics.
 */
@Mixin(GuiGraphicsExtractor.class)
public class TooltipScrollMixin {
    private static final int[] dusk$pos = new int[2];

    @WrapOperation(method = "tooltip", require = 1,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;positionTooltip(IIIIII)Lorg/joml/Vector2ic;"))
    private Vector2ic dusk$scrollTooltip(ClientTooltipPositioner positioner, int screenW, int screenH, int x, int y, int w, int h,
                                         Operation<Vector2ic> original) {
        Vector2ic at = original.call(positioner, screenW, screenH, x, y, w, h);
        ScrollableTooltips.place(screenW, screenH, at.x(), at.y(), w, h, dusk$pos);
        return dusk$pos[0] == at.x() && dusk$pos[1] == at.y() ? at : new Vector2i(dusk$pos[0], dusk$pos[1]);
    }
}
