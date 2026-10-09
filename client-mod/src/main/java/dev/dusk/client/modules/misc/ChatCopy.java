package dev.dusk.client.modules.misc;

import dev.dusk.client.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Chat copy, as Lunar and Chat Patches have it (written from how they behave,
 * not their code): Ctrl-click (Cmd-click on macOS) a chat line to copy the
 * whole message it belongs to, as it arrived, without timestamps or heads.
 */
public class ChatCopy extends Module {
    private static ChatCopy instance;
    /** Each drawn chat line to the message it was wrapped from; lines chat drops are collected. */
    private static final Map<FormattedCharSequence, Component> SOURCES = new WeakHashMap<>();
    private static Component pending;

    public ChatCopy() {
        super("chatcopy", "Chat Copy", Category.MISC,
                "Ctrl-click (Cmd-click on macOS) a chat message to copy it.");
        instance = this;
        setEnabled(true);
    }

    public static boolean active() {
        ChatCopy m = instance;
        return m != null && m.enabled();
    }

    /** The message about to be laid out into lines, before any decoration. */
    public static void laying(Component content) {
        pending = content;
    }

    /** The lines chat just wrapped the {@link #laying} message into. */
    public static List<FormattedCharSequence> laidOut(List<FormattedCharSequence> lines) {
        Component source = pending;
        pending = null;
        if (source != null) for (FormattedCharSequence line : lines) SOURCES.put(line, source);
        return lines;
    }

    /** Copies the message {@code line} belongs to; false when there's no line to copy. */
    public static boolean copy(FormattedCharSequence line) {
        if (line == null) return false;
        Component source = SOURCES.get(line);
        String text = source != null ? source.getString() : plain(line);
        if (text.isBlank()) return false;
        Minecraft mc = Minecraft.getInstance();
        mc.keyboardHandler.setClipboard(text);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        return true;
    }

    private static String plain(FormattedCharSequence line) {
        StringBuilder sb = new StringBuilder();
        line.accept((index, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });
        return sb.toString();
    }
}
