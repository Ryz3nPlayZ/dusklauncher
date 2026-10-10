package dev.dusk.client.mixin.totem;

import dev.dusk.client.modules.hud.TotemCounter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Totem Counter on servers that draw nametags as text displays riding the
 * player: the line holding their name gets the count. The cached lines are
 * replaced as a whole so the background width and centring agree with it.
 */
@Mixin(DisplayRenderer.TextDisplayRenderer.class)
public abstract class TotemTextDisplayMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Display$TextDisplay;Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;F)V",
            at = @At("RETURN"))
    private void duskclient$popsOnTextNametag(Display.TextDisplay entity, TextDisplayEntityRenderState state, float partialTick, CallbackInfo ci) {
        if (state.cachedInfo == null || !(entity.getVehicle() instanceof Player player)) return;
        List<Display.TextDisplay.CachedLine> lines = state.cachedInfo.lines();
        for (int i = 0; i < lines.size(); i++) {
            Component line = TotemCounter.textDisplayLine(player, lines.get(i).contents());
            if (line == null) continue;
            List<Display.TextDisplay.CachedLine> replaced = new ArrayList<>(lines);
            replaced.set(i, new Display.TextDisplay.CachedLine(line.getVisualOrderText(), Minecraft.getInstance().font.width(line)));
            int width = replaced.stream().mapToInt(Display.TextDisplay.CachedLine::width).max().orElse(state.cachedInfo.width());
            state.cachedInfo = new Display.TextDisplay.CachedInfo(replaced, width);
            return;
        }
    }
}
