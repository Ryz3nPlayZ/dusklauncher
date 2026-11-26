package org.polyfrost.polyplus.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.ClientBrandRetriever;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientBrandRetriever.class)
public abstract class MixinClientBrandRetriever {
    @ModifyReturnValue(method = "getClientModName", at = @At("RETURN"))
    private static String polyplus$duskBrand(String original) {
        //? if = 1.8.9 {
        /*return "fabric".equals(original) ? "dusk" : original;
        *///?} else
        return "fabric".equals(original) ? "dusk (fabric)" : original;
    }
}
