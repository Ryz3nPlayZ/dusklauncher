package dev.dusk.client.mixin.enchant;

import dev.dusk.client.gui.GraphicsCanvas;
import dev.dusk.client.modules.misc.EnchantOracle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Enchant Oracle's panel beside the enchanting table, drawn after the screen. */
@Mixin(EnchantmentScreen.class)
public abstract class EnchantScreenMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void dusk$oracle(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        EnchantmentScreen self = (EnchantmentScreen) (Object) this;
        EnchantOracle.draw(new GraphicsCanvas(g, Minecraft.getInstance().font), self.width, self.height);
    }
}
