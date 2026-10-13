package dev.dusk.client.gui;

import net.minecraft.network.chat.Component;

/** Before 1.21.10 the menus keep the default font; see the 1.21.11 layer. */
public final class Sans {
    private Sans() {}

    public static boolean supported() {
        return false;
    }

    public static Component wrap(Component text, float scale) {
        return text;
    }

    public static Component wrap(String text, float scale) {
        return Component.literal(text);
    }
}
