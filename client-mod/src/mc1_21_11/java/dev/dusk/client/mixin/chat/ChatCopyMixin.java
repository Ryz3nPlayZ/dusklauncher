package dev.dusk.client.mixin.chat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.dusk.client.modules.misc.ChatCopy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link ChatCopy}: a Ctrl/Cmd left click in chat hit-tests with a finder
 * that also remembers the line it landed on, and copies that line's message
 * instead of acting on whatever style was clicked.
 */
@Mixin(ChatScreen.class)
public abstract class ChatCopyMixin {
    @ModifyExpressionValue(method = "mouseClicked", at = @At(value = "NEW",
            target = "net/minecraft/client/gui/ActiveTextCollector$ClickableStyleFinder"))
    private ActiveTextCollector.ClickableStyleFinder duskclient$lineFinder(ActiveTextCollector.ClickableStyleFinder finder,
                                                                          @Local(argsOnly = true) MouseButtonEvent event) {
        if (!ChatCopy.active() || event.button() != 0 || !event.hasControlDownWithQuirk()) return finder;
        return new ChatLineFinder(Minecraft.getInstance().font, (int) event.x(), (int) event.y());
    }

    @Inject(method = "mouseClicked", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/ActiveTextCollector$ClickableStyleFinder;result()Lnet/minecraft/network/chat/Style;"),
            cancellable = true)
    private void duskclient$copyLine(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir,
                                     @Local ActiveTextCollector.ClickableStyleFinder finder) {
        if (finder instanceof ChatLineFinder lines && ChatCopy.copy(lines.line())) cir.setReturnValue(true);
    }
}
