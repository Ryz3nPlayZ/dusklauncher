package dev.dusk.client.mixin;

import dev.dusk.client.modules.render.ScoreboardTweaks;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.scoreboard.ScoreObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The sidebar, handed to the Scoreboard module to hide or redraw. */
@Mixin(GuiIngame.class)
public abstract class GuiIngameMixin {
    @Inject(method = "renderScoreboard", at = @At("HEAD"), cancellable = true)
    private void dusk$scoreboard(ScoreObjective objective, ScaledResolution scaledRes, CallbackInfo ci) {
        if (!ScoreboardTweaks.active()) return;
        if (ScoreboardTweaks.hidden()) {
            ci.cancel();
            return;
        }
        ScoreboardTweaks.render(net.minecraft.client.Minecraft.getMinecraft(), objective, scaledRes);
        ci.cancel();
    }
}
