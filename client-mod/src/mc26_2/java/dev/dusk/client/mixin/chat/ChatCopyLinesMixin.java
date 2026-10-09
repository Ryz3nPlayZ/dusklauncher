package dev.dusk.client.mixin.chat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.dusk.client.modules.misc.ChatCopy;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** Tells {@link ChatCopy} which message each wrapped chat line came from. */
@Mixin(ChatComponent.class)
public abstract class ChatCopyLinesMixin {
    @ModifyExpressionValue(method = "addMessageToDisplayQueue", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/chat/GuiMessage;splitLines(Lnet/minecraft/client/gui/Font;I)Ljava/util/List;"))
    private List<FormattedCharSequence> duskclient$lines(List<FormattedCharSequence> lines) {
        return ChatCopy.laidOut(lines);
    }
}
