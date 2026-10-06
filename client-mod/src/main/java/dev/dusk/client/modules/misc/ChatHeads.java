package dev.dusk.client.modules.misc;

import dev.dusk.client.compat.ChatCompat;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * The sender's face in front of their chat lines, like the Chat Heads mod
 * (reimplemented from how it behaves, no code taken). The head is a text
 * glyph (1.21.9+), so it sits in the line itself; only the drawn line
 * changes, chat history keeps the message as it came in.
 */
public class ChatHeads extends Module {
    private static ChatHeads instance;

    private final BoolSetting hat = add(new BoolSetting("hat", "Show hat layer", true));

    public ChatHeads() {
        super("chatheads", "Chat Heads", Category.MISC, "Shows the sender's head in front of chat messages.");
        instance = this;
        setEnabled(true);
    }

    /** Called as a message is laid out into chat lines. */
    public static Component decorate(Component content) {
        ChatHeads m = instance;
        if (m == null || !m.enabled()) return content;
        PlayerInfo sender = ChatSender.of(content.getString());
        if (sender == null) return content;
        Component head = ChatCompat.head(sender, m.hat.get());
        if (head == null) return content;
        return Component.empty().append(head).append(Component.literal(" ").withStyle(Style.EMPTY)).append(content);
    }
}
