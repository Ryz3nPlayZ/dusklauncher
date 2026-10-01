package dev.dusk.client.modules.misc;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.IntSetting;

/**
 * Longer chat history, the idea of Wurst's InfiniChat and Meteor's
 * BetterChat (both GPL, so written from how they behave, not their code):
 * chat keeps more than vanilla's last 100 messages to scroll back through.
 */
public class ChatHistory extends Module {
    private static ChatHistory instance;

    private final IntSetting length = add(new IntSetting("length", "Messages kept", 1000, 100, 10000, 100, ""));

    public ChatHistory() {
        super("chathistory", "Chat History", Category.MISC,
                "Keeps more chat to scroll back through than vanilla's last 100 messages.");
        instance = this;
        setEnabled(true);
    }

    /** How many messages chat keeps; {@code vanilla} while this is off. */
    public static int length(int vanilla) {
        ChatHistory m = instance;
        return m != null && m.enabled() ? Math.max(vanilla, m.length.get()) : vanilla;
    }
}
