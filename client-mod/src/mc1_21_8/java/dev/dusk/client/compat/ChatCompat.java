package dev.dusk.client.compat;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Tab-list players in chat lines. Before 1.21.9 text can't hold a head. */
public final class ChatCompat {
    private ChatCompat() {}

    public static final boolean HEADS = false;

    public static String name(PlayerInfo player) {
        return player.getProfile().getName();
    }

    @Nullable
    public static Component head(PlayerInfo player, boolean hat) {
        return null;
    }
}
