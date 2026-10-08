package dev.dusk.client.modules.misc;

import dev.dusk.client.compat.Input;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.KeySetting;
import dev.dusk.client.module.setting.TextSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Chat Macros: bind a key to a chat line or a /command ("gg", "/spawn").
 * Six slots, unbound to start. At most one macro a second, so a held or
 * mashed key can't flood the server.
 */
public class ChatMacros extends Module {
    private static final int SLOTS = 6;
    private static final long COOLDOWN_MS = 1000;

    private final KeySetting[] keys = new KeySetting[SLOTS];
    private final TextSetting[] texts = new TextSetting[SLOTS];
    private long lastSent;

    public ChatMacros() {
        super("chatmacros", "Chat Macros", Category.MISC,
                "Bind keys to send a chat message or a /command.");
        for (int i = 0; i < SLOTS; i++) {
            String group = "Macro " + (i + 1);
            keys[i] = add(new KeySetting("macro_" + (i + 1), "Key", Input.UNKNOWN), group);
            texts[i] = add(new TextSetting("text" + (i + 1), "Sends", "", 256), group);
        }
        setEnabled(true);
    }

    /** Every client tick: sends the line for any macro key pressed. */
    public void tickKeys() {
        Minecraft mc = Minecraft.getInstance();
        for (int i = 0; i < SLOTS; i++) {
            while (keys[i].mapping().consumeClick()) {
                if (enabled() && mc.player != null) send(mc.player.connection, texts[i].get().trim());
            }
        }
    }

    private void send(ClientPacketListener connection, String line) {
        long now = System.currentTimeMillis();
        if (line.isEmpty() || now - lastSent < COOLDOWN_MS) return;
        lastSent = now;
        if (line.startsWith("/")) connection.sendCommand(line.substring(1));
        else connection.sendChat(line);
    }
}
