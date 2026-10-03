package dev.dusk.client.mixin.tweaks;

import dev.dusk.client.modules.render.BossBarTweaks;
import dev.dusk.client.modules.render.ScoreboardTweaks;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves, resizes or hides the scoreboard, boss bars and titles. Each part is
 * drawn inside its own pose push, scaled around the edge it sits against.
 */
@Mixin(Hud.class)
public class HudLayoutMixin {
    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void duskclient$scoreboardStart(GuiGraphicsExtractor g, Objective objective, CallbackInfo ci) {
        if (ScoreboardTweaks.hidden()) {
            ci.cancel();
            return;
        }
        ScoreboardTweaks.setDrawing(true);
        push(g, g.guiWidth(), g.guiHeight() / 2f, ScoreboardTweaks.scale(),
                ScoreboardTweaks.offsetX(), ScoreboardTweaks.offsetY());
    }

    @Inject(method = "displayScoreboardSidebar", at = @At("RETURN"))
    private void duskclient$scoreboardEnd(GuiGraphicsExtractor g, Objective objective, CallbackInfo ci) {
        ScoreboardTweaks.setDrawing(false);
        g.pose().popMatrix();
    }

    @Inject(method = "extractBossOverlay", at = @At("HEAD"), cancellable = true)
    private void duskclient$bossStart(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
        if (BossBarTweaks.bossHidden()) {
            ci.cancel();
            return;
        }
        push(g, g.guiWidth() / 2f, 0, BossBarTweaks.bossScale(), 0, BossBarTweaks.bossY());
    }

    @Inject(method = "extractBossOverlay", at = @At("RETURN"))
    private void duskclient$bossEnd(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
        g.pose().popMatrix();
    }

    @Inject(method = "extractTitle", at = @At("HEAD"), cancellable = true)
    private void duskclient$titleStart(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
        if (BossBarTweaks.titleHidden()) {
            ci.cancel();
            return;
        }
        push(g, g.guiWidth() / 2f, g.guiHeight() / 2f, BossBarTweaks.titleScale(), 0, BossBarTweaks.titleY());
    }

    @Inject(method = "extractTitle", at = @At("RETURN"))
    private void duskclient$titleEnd(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
        g.pose().popMatrix();
    }

    private static void push(GuiGraphicsExtractor g, float pivotX, float pivotY, float scale, int dx, int dy) {
        g.pose().pushMatrix();
        g.pose().translate(pivotX + dx, pivotY + dy);
        g.pose().scale(scale, scale);
        g.pose().translate(-pivotX, -pivotY);
    }
}
