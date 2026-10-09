package dev.dusk.client.modules.misc;

import dev.dusk.client.compat.ChatCompat;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.TextSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A chat message that says your name (or one of your words) gets the word
 * coloured and a ping, so it doesn't scroll past. Your own messages don't
 * count. Links and hover text in the message keep working.
 */
public class ChatMentions extends Module {
    private static ChatMentions instance;

    private final BoolSetting ownName = add(new BoolSetting("ownName", "My username", true));
    private final TextSetting words = add(new TextSetting("words", "Other words (commas between)", "", 128));
    private final BoolSetting sound = add(new BoolSetting("sound", "Ping sound", true));
    private final ColorSetting color = add(new ColorSetting("color", "Highlight colour", 0xFFFFFF55));

    @Nullable private Pattern pattern;
    private String patternFor = "";
    private long lastPing;

    public ChatMentions() {
        super("chatmentions", "Chat Mentions", Category.MISC,
                "Highlights and pings chat messages that mention you.");
        instance = this;
        setEnabled(true);
    }

    /** Called with each message before chat stores it. */
    public static Component mention(Component text) {
        ChatMentions m = instance;
        if (m == null || !m.enabled()) return text;
        Pattern p = m.pattern();
        if (p == null) return text;
        String plain = text.getString();
        if (!p.matcher(plain).find()) return text;
        String own = Minecraft.getInstance().getUser().getName();
        PlayerInfo sender = ChatSender.of(plain);
        if (sender != null && ChatCompat.name(sender).equalsIgnoreCase(own)) return text;

        long now = System.currentTimeMillis();
        if (m.sound.get() && now - m.lastPing > 500) {
            m.lastPing = now;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 0.6f));
        }
        return m.highlight(text, p);
    }

    /**
     * The message rebuilt piece by piece with each match recoloured. Every
     * piece keeps its own style (links, hover text, fonts, sprites), and a
     * message with nothing to colour comes back as it was.
     */
    private Component highlight(Component text, Pattern p) {
        TextColor tint = TextColor.fromRgb(color.get() & 0xFFFFFF);
        MutableComponent out = Component.empty();
        boolean[] any = {false};
        text.visit((style, s) -> {
            Matcher mt = p.matcher(s);
            int last = 0;
            while (mt.find()) {
                any[0] = true;
                if (mt.start() > last) out.append(Component.literal(s.substring(last, mt.start())).withStyle(style));
                out.append(Component.literal(mt.group()).withStyle(style.withColor(tint)));
                last = mt.end();
            }
            if (last < s.length()) out.append(Component.literal(s.substring(last)).withStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return any[0] ? out : text;
    }

    /** Whole words, any case: your name and the extra words; null when there's nothing to look for. */
    @Nullable
    private Pattern pattern() {
        // the name is part of the key: an account switch mid-session looks for the new one
        String own = ownName.get() ? Minecraft.getInstance().getUser().getName() : "";
        String key = own + "|" + words.get();
        if (!key.equals(patternFor)) {
            patternFor = key;
            List<String> alts = new ArrayList<>();
            if (!own.isEmpty()) alts.add(Pattern.quote(own));
            for (String w : words.get().split(",")) {
                if (!w.isBlank()) alts.add(Pattern.quote(w.trim()));
            }
            pattern = alts.isEmpty() ? null
                    : Pattern.compile("(?<![A-Za-z0-9_])(?:" + String.join("|", alts) + ")(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
        }
        return pattern;
    }
}
