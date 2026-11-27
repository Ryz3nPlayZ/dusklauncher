package dev.dusk.client.social;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who may join a world this game hosts without a Mojang login: friends whose Dusk account is an
 * offline one (its id is the offline UUID of its name, as the launcher and server make it).
 * They join under that same offline profile, so an online player's name never gets through here.
 */
public final class HostedLogins {
    private static final Logger LOGGER = LoggerFactory.getLogger("DuskClient");
    /** A name not on the list asks the server again at most this often. */
    private static final long RECHECK_MS = 5_000;

    private static volatile Set<String> offlineFriends = Set.of();
    private static volatile long fetchedAt;

    private HostedLogins() {}

    /** Called on the login thread when {@code name} says hello to a hosted world. Blocks briefly on a miss. */
    public static boolean mayJoinOffline(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (offlineFriends.contains(key)) return true;
        synchronized (HostedLogins.class) {
            if (System.currentTimeMillis() - fetchedAt >= RECHECK_MS) refresh();
        }
        return offlineFriends.contains(key);
    }

    private static void refresh() {
        fetchedAt = System.currentTimeMillis();
        try {
            Set<String> names = new HashSet<>();
            for (Social.Friend friend : Social.friends()) {
                if (friend.username() != null && Social.same(friend.uuid(), offlineUuid(friend.username()).toString())) {
                    names.add(friend.username().toLowerCase(Locale.ROOT));
                }
            }
            offlineFriends = Set.copyOf(names);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DuskHost] couldn't load friends for offline logins: {}", e.toString());
        }
    }

    static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
