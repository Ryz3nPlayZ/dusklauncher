package dev.dusk.client.modules.misc;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.jetbrains.annotations.Nullable;

/** Show-text hover events, before 1.21.5 turned them into a record. */
final class HoverText {
    private HoverText() {}

    static HoverEvent of(Component text) {
        return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
    }

    /** The text a show-text hover shows; null for no hover or another kind. */
    @Nullable
    static Component text(@Nullable HoverEvent event) {
        return event == null ? null : event.getValue(HoverEvent.Action.SHOW_TEXT);
    }
}
