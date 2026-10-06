package dev.dusk.client.compat;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.objects.PlayerSprite;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jetbrains.annotations.Nullable;

/** Tab-list players in chat lines. 1.21.9+ flavour (text can hold a player head). */
public final class ChatCompat {
    private ChatCompat() {}

    /** Whether a head can be drawn inside chat text (object components came in 1.21.9). */
    public static final boolean HEADS = true;

    public static String name(PlayerInfo player) {
        return player.getProfile().name();
    }

    /** The player's face as one glyph of text, hat layer optional. */
    @Nullable
    public static Component head(PlayerInfo player, boolean hat) {
        return Component.object(new PlayerSprite(ResolvableProfile.createResolved(player.getProfile()), hat));
    }
}
