package dev.fasterlauncher.client.mixin;

import dev.fasterlauncher.client.gui.DuskTitleScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes the vanilla title screen to our themed menu while no world is
 * loaded. Safe from recursion: DuskTitleScreen is not a TitleScreen, so the
 * re-entrant setScreen call passes through untouched.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void fasterclient$routeTitle(Screen screen, CallbackInfo ci) {
        MinecraftClient self = (MinecraftClient) (Object) this;
        if (screen instanceof TitleScreen && self.world == null) {
            ci.cancel();
            self.setScreen(new DuskTitleScreen());
        }
    }
}
