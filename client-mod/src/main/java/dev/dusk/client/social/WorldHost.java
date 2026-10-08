package dev.dusk.client.social;

import dev.dusk.client.compat.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HOST in the launcher's WORLDS tab: the launcher starts the game with
 * {@code -Ddusk.host} (and the e4mc relay mod beside DuskClient), and every
 * singleplayer world opened this session is opened to other players as soon
 * as it loads — the same as Open to LAN, with the world's own game mode and
 * cheat setting. e4mc then relays it under a public address and logs it; the
 * launcher reads that line and shows friends where to join.
 */
public final class WorldHost {
    private static final Logger LOGGER = LoggerFactory.getLogger("DuskClient");
    private static final boolean ENABLED = System.getProperty("dusk.host") != null;

    private WorldHost() {}

    /** On joining a world: publish it if this is a hosting session and it isn't already open. */
    public static void onJoin(Minecraft mc) {
        if (!ENABLED) return;
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || server.isPublished()) return;
        int port = HttpUtil.getAvailablePort();
        if (Compat.publishLan(server, port)) LOGGER.info("[DuskHost] open on port {}", port);
        else LOGGER.warn("[DuskHost] could not open the world to other players");
    }
}
