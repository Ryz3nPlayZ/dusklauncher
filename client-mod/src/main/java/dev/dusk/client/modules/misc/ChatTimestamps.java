package dev.dusk.client.modules.misc;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Plague's Chat Timestamps (MIT, PlagueTR — see NOTICE): a gray "[HH:mm:ss]"
 * in front of each chat line, or in its hover text instead. Only the drawn
 * line changes; chat history keeps the message as it came in.
 */
public class ChatTimestamps extends Module {
    private static ChatTimestamps instance;

    private final ChoiceSetting format = add(new ChoiceSetting("timestampFormat", "Format",
            "[HH:mm:ss]", "[HH:mm:ss]", "[HH:mm]", "[hh:mm:ss a]", "[hh:mm a]"));
    private final BoolSetting hover = add(new BoolSetting("enableHover", "Show on hover instead", false));

    private String formatPattern;
    private SimpleDateFormat sdf;

    public ChatTimestamps() {
        super("chattimestamps", "Chat Timestamps", Category.MISC, "Shows when each chat message arrived.");
        instance = this;
    }

    /**
     * Called as a message is laid out into chat lines. {@code addedTime} is
     * how many GUI ticks ago it arrived, so a relayout keeps the original time.
     */
    public static Component decorate(Component content, int ageTicks) {
        ChatTimestamps m = instance;
        if (m == null || !m.enabled()) return content;
        long arrived = System.currentTimeMillis() - Math.max(0, ageTicks) * 50L;
        MutableComponent ts = Component.literal(m.formatter().format(new Date(arrived))).withStyle(ChatFormatting.GRAY);
        if (!m.hover.get()) {
            return Component.empty().append(ts).append(Component.literal(" ").withStyle(Style.EMPTY))
                    .append(content);
        }
        Style original = content.getStyle();
        HoverEvent existing = original.getHoverEvent();
        Component shown = HoverText.text(existing);
        if (existing != null && shown == null) return content;
        Component combined = shown == null ? ts : ts.copy().append("\n").append(shown);
        return content.copy().withStyle(original.withHoverEvent(HoverText.of(combined)));
    }

    private SimpleDateFormat formatter() {
        if (sdf == null || !format.get().equals(formatPattern)) {
            formatPattern = format.get();
            sdf = new SimpleDateFormat(formatPattern);
        }
        return sdf;
    }
}
