package dev.dusk.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Flex-HUD's PingUtils: a ping request to the server every second, averaged
 * over the last 20 answers. The pongs come in through PingMixin.
 */
public final class PingTracker {
    private static final int MAX = 20;
    private static final Deque<Long> pings = new ArrayDeque<>();
    private static long sum;
    private static long lastSent;

    private PingTracker() {}

    /** Called every client tick while the Ping element is on. */
    public static void tick(Minecraft mc) {
        var conn = mc.getConnection();
        if (conn == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSent < 1000) return;
        lastSent = now;
        conn.send(new ServerboundPingRequestPacket(now));
    }

    public static void onPong(long sentAt) {
        long ping = System.currentTimeMillis() - sentAt;
        pings.addLast(ping);
        sum += ping;
        if (pings.size() > MAX) sum -= pings.removeFirst();
    }

    public static long ping() {
        return pings.isEmpty() ? 0 : sum / pings.size();
    }

    public static void reset() {
        pings.clear();
        sum = 0;
        lastSent = 0;
    }
}
