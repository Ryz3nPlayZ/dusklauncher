package dev.dusk.client.mixin;

import dev.dusk.client.gui.DuskTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes the vanilla title screen to our themed menu while no world is
 * loaded. Safe from recursion: DuskTitleScreen is not a TitleScreen, so the
 * re-entrant setScreen call passes through untouched.
 */
@Mixin(Minecraft.class)
public abstract class TitleRouteMixin {
    /** Fabric's client gametest harness insists on ending on a vanilla TitleScreen. */
    @Unique
    private static final boolean duskclient$GAMETEST = System.getProperty("fabric.client.gametest") != null;

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void duskclient$routeTitle(Screen screen, CallbackInfo ci) {
        Minecraft self = (Minecraft) (Object) this;
        if ((screen instanceof TitleScreen || screen == null) && self.level == null && !duskclient$GAMETEST) {
            ci.cancel();
            self.setScreen(new DuskTitleScreen());
        }
    }
}
