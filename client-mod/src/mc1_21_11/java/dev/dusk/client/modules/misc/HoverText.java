package dev.dusk.client.modules.misc;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.jetbrains.annotations.Nullable;

/** Show-text hover events, which 1.21.5 turned into a record. */
final class HoverText {
    private HoverText() {}

    static HoverEvent of(Component text) {
        return new HoverEvent.ShowText(text);
    }

    /** The text a show-text hover shows; null for no hover or another kind. */
    @Nullable
    static Component text(@Nullable HoverEvent event) {
        return event instanceof HoverEvent.ShowText(Component value) ? value : null;
    }
}
