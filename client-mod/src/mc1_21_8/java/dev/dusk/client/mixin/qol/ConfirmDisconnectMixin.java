package dev.dusk.client.mixin.qol;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.modules.misc.ConfirmDisconnect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holds back the pause menu's disconnect button until it is confirmed ({@link ConfirmDisconnect}). */
@Mixin(Button.class)
public abstract class ConfirmDisconnectMixin {
    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void duskclient$confirm(CallbackInfo ci) {
        if (Compat.currentScreen(Minecraft.getInstance()) instanceof PauseScreen pause
                && ((PauseScreenAccessor) pause).duskclient$disconnectButton() == (Object) this
                && ConfirmDisconnect.holdBack((Button) (Object) this, ((Button) (Object) this)::onPress)) {
            ci.cancel();
        }
    }
}
