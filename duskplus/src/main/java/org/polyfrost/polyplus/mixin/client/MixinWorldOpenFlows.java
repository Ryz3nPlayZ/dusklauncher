package org.polyfrost.polyplus.mixin.client;

//? if > 1.8.9 {
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.polyfrost.polyplus.client.lobby.LocalJoin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A world open while another singleplayer join is still loading is a double click: drop it. A server left over from
 * a join that was abandoned still holds its world, so stop it before the new world takes its lock.
 */
@Mixin(WorldOpenFlows.class)
public class MixinWorldOpenFlows {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "openWorld", at = @At("HEAD"), cancellable = true)
    private void polyplus$oneJoinAtATime(String levelId, Runnable onFail, CallbackInfo ci) {
        if (polyplus$blocked()) ci.cancel();
    }

    @Inject(method = "createFreshLevel", at = @At("HEAD"), cancellable = true)
    private void polyplus$oneFreshJoinAtATime(CallbackInfo ci) {
        if (polyplus$blocked()) ci.cancel();
    }

    @Unique
    private boolean polyplus$blocked() {
        if (LocalJoin.onLoadingScreen()) return true;
        LocalJoin.clearStranded(minecraft);
        return false;
    }
}
//?}
