package dev.dusk.client.mixin.chat;

import dev.dusk.client.modules.misc.ChatHeads;
import dev.dusk.client.modules.misc.ChatMentions;
import dev.dusk.client.modules.misc.ChatTimestamps;
import dev.dusk.client.modules.misc.CompactChat;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Chat hooks for {@link ChatMentions}, {@link CompactChat} (Compact Chat's
 * ChatHudMixin, applied as late as it is there), {@link ChatHeads} and
 * {@link ChatTimestamps} (Plague's MixinChatComponent).
 */
@Mixin(value = ChatComponent.class, priority = Integer.MAX_VALUE)
public abstract class ChatStackMixin {
    private static final String ADD = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V";

    @Shadow @Final private List<GuiMessage> allMessages;

    @Shadow
    protected abstract void refreshTrimmedMessages();

    @ModifyVariable(method = ADD, at = @At("HEAD"), argsOnly = true)
    private Component duskclient$compact(Component content) {
        return CompactChat.compact(ChatMentions.mention(content), allMessages, GuiMessage::content, this::refreshTrimmedMessages);
    }

    @Inject(method = "clearMessages", at = @At("HEAD"))
    private void duskclient$clear(boolean clearHistory, CallbackInfo ci) {
        CompactChat.clear();
    }

    @ModifyVariable(method = "addMessageToDisplayQueue", at = @At("HEAD"), argsOnly = true)
    private GuiMessage duskclient$timestamp(GuiMessage message) {
        Component content = ChatTimestamps.decorate(ChatHeads.decorate(message.content()), Minecraft.getInstance().gui.getGuiTicks() - message.addedTime());
        if (content == message.content()) return message;
        return new GuiMessage(message.addedTime(), content, message.signature(), message.tag());
    }
}
