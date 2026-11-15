package dev.dusk.client.mixin;

import dev.dusk.client.modules.render.TabPing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.network.NetworkPlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The tab list's ping bars, replaced by numbers. */
@Mixin(GuiPlayerTabOverlay.class)
public abstract class GuiPlayerTabOverlayMixin {
    /** The 13 vanilla leaves for the bars; the number needs a little more. */
    @ModifyConstant(method = "renderPlayerlist", constant = @Constant(intValue = 13), require = 0)
    private int dusk$columnWidth(int original) {
        return original + TabPing.extraWidth();
    }

    @Inject(method = "drawPing", at = @At("HEAD"), cancellable = true)
    private void dusk$ping(int columnWidth, int x, int y, NetworkPlayerInfo info, CallbackInfo ci) {
        if (!TabPing.active()) return;
        ci.cancel();
        int latency = info.getResponseTime();
        String text = TabPing.text(latency);
        Minecraft.getMinecraft().fontRendererObj.drawStringWithShadow(text,
                x + columnWidth - 11 - Minecraft.getMinecraft().fontRendererObj.getStringWidth(text),
                y, TabPing.color(latency));
    }
}
