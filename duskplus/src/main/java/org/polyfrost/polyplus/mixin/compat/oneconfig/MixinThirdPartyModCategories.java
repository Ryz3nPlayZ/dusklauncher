package org.polyfrost.polyplus.mixin.compat.oneconfig;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// OneConfig downloads mod categories from Polyfrost; without them it uses its built-in defaults
@Pseudo
@Mixin(targets = "org.polyfrost.oneconfig.internal.ui.api.ThirdPartyModCategories", remap = false)
public abstract class MixinThirdPartyModCategories {
    @Inject(method = "init", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void polyplus$noDownload(CallbackInfo ci) {
        ci.cancel();
    }
}
