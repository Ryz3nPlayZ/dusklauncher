package dev.dusk.client.mixin.media;

import dev.dusk.client.media.impl.ReplayMenuScreen;
import dev.dusk.client.media.impl.ReplayPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Esc during a replay opens the replay controls instead of the pause menu. */
@Mixin(Minecraft.class)
public abstract class ReplayPauseMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void duskclient$replayMenu(Screen screen, CallbackInfo ci) {
        Minecraft self = (Minecraft) (Object) this;
        if (screen instanceof PauseScreen && ReplayPlayer.active() && self.level != null) {
            ci.cancel();
            self.setScreen(new ReplayMenuScreen());
        }
    }
}
