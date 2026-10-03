package dev.dusk.client.mixin.tweaks;

import dev.dusk.client.modules.render.ScoreboardTweaks;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.world.scores.PlayerScoreEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Blanks the red score numbers while the sidebar is being drawn; vanilla then sizes the panel without them. */
@Mixin(PlayerScoreEntry.class)
public class ScoreNumberMixin {
    @Inject(method = "formatValue", at = @At("HEAD"), cancellable = true)
    private void duskclient$blank(NumberFormat format, CallbackInfoReturnable<MutableComponent> cir) {
        if (ScoreboardTweaks.blankScore()) cir.setReturnValue(Component.empty());
    }
}
