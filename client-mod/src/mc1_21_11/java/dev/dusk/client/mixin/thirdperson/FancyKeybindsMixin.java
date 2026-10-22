package dev.dusk.client.mixin.thirdperson;

import dev.dusk.client.render.thirdperson.FancyCamera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fancy Third Person: face the crosshair point just before a click is handled. */
@Mixin(Minecraft.class)
public class FancyKeybindsMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void dusk$fancyFace(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        FancyCamera.beforeKeybinds(pressed(mc.options.keyAttack) || pressed(mc.options.keyUse) || pressed(mc.options.keyPickItem));
    }

    private static boolean pressed(KeyMapping key) {
        return key.isDown() || ((FancyKeyAccessor) key).dusk$clickCount() > 0;
    }
}
