package dev.dusk.client.mixin.chat;

import dev.dusk.client.modules.misc.ChatHistory;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Raises chat's 100-message cap to {@link ChatHistory}'s length. */
@Mixin(ChatComponent.class)
public abstract class ChatHistoryMixin {
    @ModifyConstant(method = {"addMessageToDisplayQueue", "addMessageToQueue"}, constant = @Constant(intValue = 100))
    private int duskclient$historyLength(int vanilla) {
        return ChatHistory.length(vanilla);
    }
}
