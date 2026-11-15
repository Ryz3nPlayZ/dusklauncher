package dev.dusk.client.mixin;

import dev.dusk.client.gui.GraphicsCanvas;
import dev.dusk.client.gui.ModernButtons;
import dev.dusk.client.util.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the pause menu's text buttons in the modern look (see {@link ModernButtons}). */
@Mixin(GuiButton.class)
public abstract class GuiButtonMixin {
    @Inject(method = "drawButton", at = @At("HEAD"), cancellable = true)
    private void dusk$modern(Minecraft mc, int mouseX, int mouseY, CallbackInfo ci) {
        final GuiButton b = (GuiButton) (Object) this;
        if (!b.visible) return;
        try {
            if (!ModernButtons.applies(b)) return;
            boolean hot = mouseX >= b.xPosition && mouseY >= b.yPosition
                    && mouseX < b.xPosition + b.width && mouseY < b.yPosition + b.height;
            GraphicsCanvas canvas = new GraphicsCanvas(mc, mc.fontRendererObj);
            try {
                ModernButtons.draw(canvas, b.xPosition, b.yPosition, b.width, b.height, b.displayString, hot, b.enabled);
            } finally {
                canvas.finish();
            }
            ci.cancel();
        } catch (Throwable t) {
            // vanilla draws the button instead; logged once
            Guard.fail("modern pause buttons", null, t);
        }
    }
}
