package dev.dusk.client.account;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * The local skin when the game's own profile fetch at start-up came back
 * without one (session server rate limit, a network blip): the title screen
 * and wardrobe would show Steve/Alex for the whole session. While they keep
 * showing a default skin, the account's active skin is asked of
 * api.minecraftservices.com instead, a few times with growing waits.
 */
public final class SelfSkin {
    /** How long a default skin is shown before each ask: the first covers a skin still downloading. */
    private static final long[] WAIT_MS = {3_000, 15_000, 60_000, 180_000};

    private static int tries;
    private static volatile long since;
    private static volatile boolean busy, done;

    private SelfSkin() {}

    /** The preview drew a default skin this frame; {@code use} gets the active skin (url, slim) on the render thread. */
    public static void defaultShown(BiConsumer<String, Boolean> use) {
        if (done || busy || tries >= WAIT_MS.length) return;
        long now = System.currentTimeMillis();
        if (since == 0) since = now;
        if (now - since < WAIT_MS[tries]) return;
        busy = true;
        tries++;
        CompletableFuture.runAsync(() -> {
            try {
                MojangProfile.Skin s = MojangProfile.fetch().activeSkin();
                if (s != null) {
                    done = true;
                    Minecraft.getInstance().execute(() -> use.accept(s.url(), s.slim()));
                }
            } catch (Exception ignored) {
                // offline or refused; the next wait tries again
            } finally {
                since = System.currentTimeMillis();
                busy = false;
            }
        });
    }
}
