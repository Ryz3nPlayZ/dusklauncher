package dev.dusk.client.mixin;

import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Servers like Hypixel remove teams and objectives the client never had
 * (dozens at once on every lobby or game switch). Vanilla throws on each one
 * and logs the whole stack trace, on the render thread; here a removal of
 * nothing is simply nothing.
 */
@Mixin(Scoreboard.class)
public abstract class ScoreboardMixin {
    @Inject(method = "removeTeam", at = @At("HEAD"), cancellable = true, require = 0)
    private void dusk$noTeam(ScorePlayerTeam team, CallbackInfo ci) {
        if (team == null) ci.cancel();
    }

    @Inject(method = "removeObjective", at = @At("HEAD"), cancellable = true, require = 0)
    private void dusk$noObjective(ScoreObjective objective, CallbackInfo ci) {
        if (objective == null) ci.cancel();
    }
}
