package dev.dusk.client.mixin.gui;

import dev.dusk.client.gui.GraphicsCanvas;
import dev.dusk.client.gui.ModernButtons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the pause menu's text buttons in the modern look (see {@link ModernButtons}). */
@Mixin(AbstractButton.class)
public abstract class ModernButtonMixin {
    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void duskclient$modern(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!ModernButtons.applies(this)) return;
        AbstractWidget w = (AbstractWidget) (Object) this;
        ModernButtons.draw(new GraphicsCanvas(graphics, Minecraft.getInstance().font), w.getX(), w.getY(), w.getWidth(), w.getHeight(),
                w.getMessage().getString(), w.isHoveredOrFocused(), w.active);
        ci.cancel();
    }
}
