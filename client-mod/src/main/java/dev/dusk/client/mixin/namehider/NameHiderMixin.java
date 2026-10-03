package dev.dusk.client.mixin.namehider;

import dev.dusk.client.modules.misc.NameHider;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every string the font draws or measures passes through StringDecomposer, so
 * swapping the name here covers chat, the tab list, scoreboards and name tags
 * at once. getPlainText (copying, narration) is left untouched.
 */
@Mixin(StringDecomposer.class)
public class NameHiderMixin {
    @Inject(method = "iterate", at = @At("HEAD"), cancellable = true)
    private static void duskclient$iterate(String text, Style style, FormattedCharSink sink,
                                          CallbackInfoReturnable<Boolean> cir) {
        String shown = NameHider.apply(text);
        if (shown == text) return;
        NameHider.bypass(true);
        try {
            cir.setReturnValue(StringDecomposer.iterate(shown, style, sink));
        } finally {
            NameHider.bypass(false);
        }
    }

    @Inject(method = "iterateBackwards", at = @At("HEAD"), cancellable = true)
    private static void duskclient$iterateBackwards(String text, Style style, FormattedCharSink sink,
                                                   CallbackInfoReturnable<Boolean> cir) {
        String shown = NameHider.apply(text);
        if (shown == text) return;
        NameHider.bypass(true);
        try {
            cir.setReturnValue(StringDecomposer.iterateBackwards(shown, style, sink));
        } finally {
            NameHider.bypass(false);
        }
    }

    @Inject(method = "iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void duskclient$iterateFormatted(String text, int offset, Style current, Style reset,
                                                   FormattedCharSink sink, CallbackInfoReturnable<Boolean> cir) {
        if (offset != 0) return;
        String shown = NameHider.apply(text);
        if (shown == text) return;
        NameHider.bypass(true);
        try {
            cir.setReturnValue(StringDecomposer.iterateFormatted(shown, 0, current, reset, sink));
        } finally {
            NameHider.bypass(false);
        }
    }

    @Inject(method = "getPlainText", at = @At("HEAD"))
    private static void duskclient$plainStart(FormattedText text, CallbackInfoReturnable<String> cir) {
        NameHider.bypass(true);
    }

    @Inject(method = "getPlainText", at = @At("RETURN"))
    private static void duskclient$plainEnd(FormattedText text, CallbackInfoReturnable<String> cir) {
        NameHider.bypass(false);
    }
}
