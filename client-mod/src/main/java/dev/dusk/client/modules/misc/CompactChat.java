package dev.dusk.client.modules.misc;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * Compact Chat (MIT, Caoimhe Byrne — see NOTICE): a message that already
 * came in is taken out of the chat history and comes back at the bottom with
 * how many times it was sent, " (3)", instead of filling the chat.
 */
public class CompactChat extends Module {
    private static final List<String> COMMON_SEPARATORS = List.of("-----", "======");

    private static CompactChat instance;

    private final IntSetting maximumOccurrences = add(new IntSetting("maximumOccurrences", "Count up to", 100, 2, 1000, 1, ""));
    private final IntSetting ignoreFirstCharacters = add(new IntSetting("ignoreFirstCharactersCount", "Ignore first characters", 0, 0, 100, 1, ""));
    private final BoolSetting onlyConsecutive = add(new BoolSetting("onlyCompactConsecutiveMessages", "Only consecutive messages", false));
    private final BoolSetting ignoreSeparators = add(new BoolSetting("ignoreCommonSeparators", "Ignore separator lines", true));

    private final Map<String, Integer> occurrences = new HashMap<>();
    // the " (n)" counters this module appended, so they can be left out when comparing
    private final Set<Component> counters = Collections.newSetFromMap(new WeakHashMap<>());
    @Nullable private String previousMessage;

    public CompactChat() {
        super("compactchat", "Compact Chat", Category.MISC,
                "Collapses repeated chat messages into one with a count.");
        instance = this;
        setEnabled(true);
    }

    /**
     * Called with each message before chat stores it. {@code messages} is
     * chat's history, {@code contentOf} reads a history entry's text and
     * {@code refresh} rebuilds the visible lines after one is taken out.
     */
    public static <T> Component compact(Component text, List<T> messages, Function<T, Component> contentOf, Runnable refresh) {
        CompactChat m = instance;
        if (m == null || !m.enabled()) return text;
        String message = m.strip(text);
        boolean ignore = m.shouldIgnore(text, message);
        m.previousMessage = message;
        if (ignore) {
            m.occurrences.putIfAbsent(message, 1);
            return text;
        }
        int count = m.occurrences.merge(message, 1, (a, b) -> Math.min(a + b, m.maximumOccurrences.get()));
        if (count <= 1) return text;

        ListIterator<T> it = messages.listIterator();
        while (it.hasNext()) {
            MutableComponent content = contentOf.apply(it.next()).copy();
            content.getSiblings().removeIf(m.counters::contains);
            if (m.strip(content).equals(message)) {
                it.remove();
                refresh.run();
                break;
            }
        }
        int max = m.maximumOccurrences.get();
        MutableComponent counter = Component.literal(count >= max ? " (" + max + "+)" : " (" + count + ")")
                .withStyle(ChatFormatting.GRAY);
        m.counters.add(counter);
        return text.copy().append(counter);
    }

    /** Chat was cleared: forget every count. */
    public static void clear() {
        CompactChat m = instance;
        if (m != null) m.occurrences.clear();
    }

    private String strip(Component text) {
        String s = text.getString();
        int skip = ignoreFirstCharacters.get();
        return skip > 0 ? s.substring(Math.min(s.length(), skip)) : s;
    }

    private boolean shouldIgnore(Component original, String message) {
        if (original.getString().isBlank()) return true;
        if (onlyConsecutive.get()) return !message.equals(previousMessage);
        if (ignoreSeparators.get()) return COMMON_SEPARATORS.stream().anyMatch(message::contains);
        return false;
    }

    @Override
    protected void onDisable() {
        occurrences.clear();
        previousMessage = null;
    }
}
