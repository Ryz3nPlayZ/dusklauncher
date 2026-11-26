package org.polyfrost.polyplus.mixin.compat.oneconfig;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// OneConfig downloads community translations from Polyfrost; the bundled languages still work
@Pseudo
@Mixin(targets = "org.polyfrost.oneconfig.internal.l10n.CommunityTranslations", remap = false)
public abstract class MixinCommunityTranslations {
    @Inject(method = "refresh", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void polyplus$noDownload(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
