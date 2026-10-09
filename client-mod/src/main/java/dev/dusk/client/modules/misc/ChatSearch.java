package dev.dusk.client.modules.misc;

import dev.dusk.client.compat.ScreenWidgets;
import dev.dusk.client.module.Module;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Chat Search: a box in the corner of the chat screen, and while it has text
 * chat shows only the messages containing it (any case). The rest are still
 * there; they come back as the box is cleared or chat is closed. With Chat
 * History's longer scrollback this finds a coordinate or a name from an hour
 * ago.
 */
public class ChatSearch extends Module {
    private static final int BOX_WIDTH = 140;

    private static ChatSearch instance;
    /** what's in the box, as typed */
    private static String typed = "";
    /** what to match: trimmed, lower case; empty shows everything */
    private static String query = "";
    @Nullable private static Runnable refresh;

    public ChatSearch() {
        super("chatsearch", "Chat Search", Category.MISC,
                "A search box on the chat screen: chat shows only the messages containing what you type.");
        instance = this;
        setEnabled(true);
    }

    /** Chat hands over how to lay its lines out again from its full history. */
    public static void bind(Runnable refreshChat) {
        refresh = refreshChat;
    }

    /** Whether a message is left out of the chat being shown right now. */
    public static boolean hides(Component content) {
        return !query.isEmpty() && !content.getString().toLowerCase(Locale.ROOT).contains(query);
    }

    private static void search(String text) {
        typed = text;
        String q = text.strip().toLowerCase(Locale.ROOT);
        if (q.equals(query)) return;
        query = q;
        if (refresh != null) refresh.run();
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ChatScreen)) return;
            ChatSearch m = instance;
            if (m == null || !m.enabled()) return;
            EditBox box = new EditBox(client.font, w - BOX_WIDTH - 4, 4, BOX_WIDTH, 14, Component.literal("Search chat"));
            box.setHint(Component.literal("Search chat…"));
            box.setValue(typed); // the window was resized: keep the search
            box.setResponder(ChatSearch::search);
            ScreenWidgets.of(screen).add(box);
            ScreenEvents.remove(screen).register(s -> search(""));
        });
    }

    @Override
    protected void onDisable() {
        search("");
    }
}
