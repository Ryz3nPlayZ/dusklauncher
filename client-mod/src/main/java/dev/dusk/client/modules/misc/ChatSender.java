package dev.dusk.client.modules.misc;

import dev.dusk.client.compat.ChatCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who a chat line is from, guessed from the text alone since servers format
 * chat their own way ("<Name> hi", "[VIP] Name: hi", "Name joined the
 * game"): the first of its opening words that is a player's name. Players
 * seen in the tab list earlier this session still count after they leave,
 * so their lines keep their head when chat is laid out again.
 */
public final class ChatSender {
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** How many words in the sender is looked for: past the rank tags, short of the message. */
    private static final int OPENING_WORDS = 4;

    /** Lower-case name to the player's latest tab-list entry. */
    private static final Map<String, PlayerInfo> seen = new HashMap<>();
    @Nullable private static ClientPacketListener connection;
    private static int lastCount = -1;
    private static long lastRefresh;

    private ChatSender() {}

    @Nullable
    public static PlayerInfo of(String text) {
        if (!refresh()) return null;
        String plain = ChatFormatting.stripFormatting(text);
        if (plain == null) return null;
        Matcher m = WORD.matcher(plain);
        for (int i = 0; i < OPENING_WORDS && m.find(); i++) {
            PlayerInfo p = seen.get(m.group().toLowerCase(Locale.ROOT));
            if (p != null) return p;
        }
        return null;
    }

    /** Re-reads the tab list when it changed size or a second has passed; false outside a world. */
    private static boolean refresh() {
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (conn == null) return false;
        if (conn != connection) {
            connection = conn;
            seen.clear();
            lastCount = -1;
        }
        Collection<PlayerInfo> online = conn.getOnlinePlayers();
        long now = System.currentTimeMillis();
        if (online.size() != lastCount || now - lastRefresh > 1000) {
            for (PlayerInfo p : online) seen.put(ChatCompat.name(p).toLowerCase(Locale.ROOT), p);
            lastCount = online.size();
            lastRefresh = now;
        }
        return true;
    }
}
