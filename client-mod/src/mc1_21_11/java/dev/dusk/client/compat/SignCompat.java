package dev.dusk.client.compat;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

/** A sign's sides. 26.3 names them with a slot and hands its lines back as a list. */
public final class SignCompat {
    private SignCompat() {}

    public static boolean facingFront(SignBlockEntity sign, Player player) {
        return sign.isFacingFrontText(player);
    }

    public static SignText text(SignBlockEntity sign, boolean front) {
        return sign.getText(front);
    }

    public static Component[] lines(SignText text) {
        return text.getMessages(false);
    }
}
