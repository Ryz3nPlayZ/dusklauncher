package dev.dusk.client.emote;

import dev.dusk.client.DuskClient;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The emotes in the instance's {@code emotes} folder: every {@code .json}
 * animation file in it (subfolders too), read when the wheel first opens
 * and again on Reload.
 */
public final class EmoteLibrary {
    private static List<EmoteAnimation> emotes;
    private static int broken;

    private EmoteLibrary() {}

    public static Path folder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("emotes");
    }

    public static List<EmoteAnimation> all() {
        if (emotes == null) reload();
        return emotes;
    }

    /** Files that couldn't be read at the last reload. */
    public static int broken() { return broken; }

    public static void reload() {
        List<EmoteAnimation> found = new ArrayList<>();
        int bad = 0;
        Path dir = folder();
        try {
            Files.createDirectories(dir);
            try (Stream<Path> files = Files.walk(dir, 4)) {
                for (Path f : files.filter(p -> p.toString().toLowerCase().endsWith(".json")).sorted().toList()) {
                    try {
                        found.addAll(EmoteAnimation.parse(Files.readString(f, StandardCharsets.UTF_8)));
                    } catch (Exception e) {
                        bad++;
                        DuskClient.LOGGER.warn("Emotes: couldn't read {}: {}", dir.relativize(f), e.toString());
                    }
                }
            }
        } catch (IOException e) {
            DuskClient.LOGGER.warn("Emotes: couldn't list {}: {}", dir, e.toString());
        }
        found.sort(Comparator.comparing(EmoteAnimation::name, String.CASE_INSENSITIVE_ORDER));
        emotes = List.copyOf(found);
        broken = bad;
        DuskClient.LOGGER.info("Emotes: {} loaded{}", emotes.size(), bad > 0 ? ", " + bad + " file(s) unreadable" : "");
    }
}
