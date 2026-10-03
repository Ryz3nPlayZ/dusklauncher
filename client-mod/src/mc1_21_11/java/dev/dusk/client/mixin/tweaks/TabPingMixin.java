package dev.dusk.client.mixin.tweaks;

import dev.dusk.client.modules.render.TabPing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ping in milliseconds in place of the signal bars, with the column widened to fit. */
@Mixin(PlayerTabOverlay.class)
public class TabPingMixin {
    @ModifyConstant(method = "render", constant = @Constant(intValue = 13))
    private int duskclient$pingColumn(int width) {
        return TabPing.columnWidth(width);
    }

    @Inject(method = "renderPingIcon", at = @At("HEAD"), cancellable = true)
    private void duskclient$pingText(GuiGraphics g, int width, int x, int y, PlayerInfo info, CallbackInfo ci) {
        if (!TabPing.active()) return;
        ci.cancel();
        Font font = Minecraft.getInstance().font;
        int latency = info.getLatency();
        String text = TabPing.text(latency);
        g.drawString(font, text, x + width - 1 - font.width(text), y, TabPing.color(latency));
    }
}
