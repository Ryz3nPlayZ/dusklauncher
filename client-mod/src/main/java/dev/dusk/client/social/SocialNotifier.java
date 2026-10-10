package dev.dusk.client.social;

import dev.dusk.client.account.DuskAccount;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Icons;
import dev.dusk.client.gui.QuestsScreen;
import dev.dusk.client.gui.RemoteImages;
import dev.dusk.client.gui.SocialScreen;
import dev.dusk.client.gui.Theme;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps an eye on the friends list while you play, like the launcher's
 * heartbeat does for its sidebar: unread and request counts for the menus'
 * badges, and a toast in the corner when someone messages you, comes online
 * or asks to be friends. Reads only the list (never the messages), so a
 * conversation stays unread until you open it, here or in the launcher.
 * Presence is the launcher's to report; this never posts it.
 *
 * <p>It also reports play time for quests: a tick a minute while you're in a
 * world, saying whether you've touched the game lately (the service credits
 * the real time between ticks, capped, so idling or extra ticks earn nothing),
 * and a toast when a quest is ready to claim.
 */
public final class SocialNotifier {
    private static final long POLL_S = 30, PLAY_S = 60, IDLE_MS = 5 * 60_000L, TOAST_MS = 5000, SLIDE_MS = 180;
    private static final int TOAST_W = 150, TOAST_H = 28, MAX_TOASTS = 3;

    /** A friend's toast shows their face ({@code uuid}); anything else shows {@code icon}. */
    private record Toast(@Nullable String uuid, @Nullable Icons icon, String title, String body, long at) {}

    private static final ScheduledExecutorService POLL = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "duskclient-friends");
        t.setDaemon(true);
        return t;
    });
    private static final RemoteImages HEADS = new RemoteImages(0);
    private static final List<Toast> toasts = new ArrayList<>();

    private static volatile int unread, requests;
    /** Last snapshot by undashed uuid; null until the first poll (which never toasts). */
    private static Map<String, Social.Friend> last;
    private static java.util.Set<Long> knownRequests;
    private static int failures;
    private static long skipUntil;

    // play ticks: written on the client thread, read on POLL
    private static volatile boolean inWorld;
    private static volatile @Nullable String server;
    private static volatile long lastInput;
    private static float lastYaw, lastPitch;
    private static double lastMouseX, lastMouseY;
    private static volatile int ready;
    private static long questsAt;
    /** Ready quests already toasted; null until the first play tick answers. */
    private static @Nullable Set<String> toasted;

    private SocialNotifier() {}

    public static void start() {
        POLL.scheduleWithFixedDelay(SocialNotifier::poll, 8, POLL_S, TimeUnit.SECONDS);
        POLL.scheduleWithFixedDelay(SocialNotifier::play, PLAY_S, PLAY_S, TimeUnit.SECONDS);
    }

    /** Quest rewards waiting for CLAIM, as of the last play tick or quests page. */
    public static int ready() {
        return ready;
    }

    /** The quests page read the boards: take its count. */
    public static void ready(int n) {
        ready = n;
    }

    /**
     * Client tick: notes whether you're in a world and on which server, and
     * when you last looked around, moved the mouse or held a movement key.
     */
    public static void tick(Minecraft mc) {
        inWorld = mc.level != null && mc.player != null;
        var sd = mc.getCurrentServer();
        server = sd == null ? null : sd.ip;
        long now = System.currentTimeMillis();
        double mx = mc.mouseHandler.xpos(), my = mc.mouseHandler.ypos();
        if (mx != lastMouseX || my != lastMouseY) {
            lastMouseX = mx;
            lastMouseY = my;
            lastInput = now;
        }
        if (mc.player != null) {
            float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
            if (yaw != lastYaw || pitch != lastPitch) {
                lastYaw = yaw;
                lastPitch = pitch;
                lastInput = now;
            }
        }
        var o = mc.options;
        if (o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown() || o.keyJump.isDown()
                || o.keyAttack.isDown() || o.keyUse.isDown() || o.keyShift.isDown()) {
            lastInput = now;
        }
    }

    private static void play() {
        if (!inWorld) return;
        if (!DuskAccount.canSignIn()) return;
        try {
            boolean active = System.currentTimeMillis() - lastInput < IDLE_MS;
            readied(Social.play(active, server));
        } catch (Exception ignored) {
            // the next tick tries again; the service only credits time between ticks it saw
        }
    }

    private static synchronized void readied(List<Social.Ready> now) {
        ready = now.size();
        Set<String> before = toasted;
        Set<String> ids = new HashSet<>();
        for (Social.Ready r : now) ids.add(r.id());
        toasted = ids;
        if (Compat.currentScreen(Minecraft.getInstance()) instanceof QuestsScreen) return;
        if (before == null) {
            // the first tick of the session: one summary, not a toast each
            if (now.size() == 1) toast(Icons.STAR, "Quest complete", now.get(0).title() + "  +" + now.get(0).coins());
            else if (now.size() > 1) toast(Icons.STAR, now.size() + " quest rewards", "waiting to claim");
            return;
        }
        for (Social.Ready r : now) {
            if (before.contains(r.id())) continue;
            toast(Icons.STAR, r.id().equals("streak") ? "Streak kept" : "Quest complete", r.title() + "  +" + r.coins() + " coins");
        }
    }

    /** Messages waiting across every conversation. */
    public static int unread() {
        return unread;
    }

    /** Friend requests waiting for an answer. */
    public static int requests() {
        return requests;
    }

    /** What the badges count: unread messages plus requests. */
    public static int badge() {
        return unread + requests;
    }

    /** The friends page had a fresher list: take it, without toasting what it already shows. */
    public static synchronized void seen(List<Social.Friend> friends, int incoming) {
        Map<String, Social.Friend> now = new HashMap<>();
        int u = 0;
        for (Social.Friend f : friends) {
            now.put(Social.undashed(f.uuid()), f);
            u += f.unread();
        }
        last = now;
        unread = u;
        requests = incoming;
    }

    private static void poll() {
        if (System.currentTimeMillis() < skipUntil) return;
        if (!DuskAccount.canSignIn()) return;
        try {
            List<Social.Friend> friends = Social.friends();
            Social.Requests reqs = Social.requests();
            failures = 0;
            diff(friends, reqs);
            // out of a world there are no play ticks to bring the quests count, so ask now and then
            long now = System.currentTimeMillis();
            if (!inWorld && now - questsAt > 5 * 60_000L) {
                questsAt = now;
                ready = Social.quests().claimable();
            }
        } catch (Exception e) {
            // back off while the service is down or sign-in fails: 1, 2, 4... up to 16 polls
            failures = Math.min(failures + 1, 5);
            skipUntil = System.currentTimeMillis() + POLL_S * 1000L * ((1L << (failures - 1)) - 1);
        }
    }

    private static synchronized void diff(List<Social.Friend> friends, Social.Requests reqs) {
        Map<String, Social.Friend> before = last;
        java.util.Set<Long> knownBefore = knownRequests;
        Map<String, Social.Friend> now = new HashMap<>();
        int u = 0;
        for (Social.Friend f : friends) {
            now.put(Social.undashed(f.uuid()), f);
            u += f.unread();
        }
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Social.Request r : reqs.incoming()) ids.add(r.id());
        last = now;
        knownRequests = ids;
        unread = u;
        requests = reqs.incoming().size();
        if (before == null || knownBefore == null) return;
        // the open friends page shows all of this itself
        if (Compat.currentScreen(Minecraft.getInstance()) instanceof SocialScreen) return;
        if (!DuskConfig.get().friendToasts) return;
        for (Social.Friend f : friends) {
            Social.Friend old = before.get(Social.undashed(f.uuid()));
            if (old == null) continue;
            if (f.unread() > old.unread()) {
                int n = f.unread() - old.unread();
                toast(f.uuid(), f.username(), n == 1 ? "sent you a message" : "sent you " + n + " messages");
            } else if (f.online() && !old.online()) {
                toast(f.uuid(), f.username(), f.playing() != null ? "is playing " + f.playing() : "is online");
            }
        }
        for (Social.Request r : reqs.incoming()) {
            if (!knownBefore.contains(r.id())) toast(r.uuid(), r.username(), "wants to be friends");
        }
    }

    private static void toast(String uuid, String title, String body) {
        add(new Toast(uuid, null, title, body, System.currentTimeMillis()));
    }

    public static void toast(Icons icon, String title, String body) {
        add(new Toast(null, icon, title, body, System.currentTimeMillis()));
    }

    private static void add(Toast t) {
        Minecraft.getInstance().execute(() -> {
            toasts.add(t);
            while (toasts.size() > MAX_TOASTS) toasts.remove(0);
        });
    }

    /** The toasts, top right, sliding in and out. HUD render, render thread. */
    public static void draw(Canvas c, int screenW) {
        if (toasts.isEmpty()) return;
        long now = System.currentTimeMillis();
        toasts.removeIf(t -> now - t.at > TOAST_MS);
        int y = 4;
        for (Toast t : toasts) {
            long age = now - t.at;
            float in = Math.min(1f, age / (float) SLIDE_MS), out = Math.min(1f, (TOAST_MS - age) / (float) SLIDE_MS);
            float k = Math.min(in, out);
            int x = screenW - 4 - Math.round((TOAST_W + 4) * k) + 4;
            Theme.box(c, x, y, TOAST_W, TOAST_H, Theme.Family.PANEL, false, false);
            if (t.uuid != null) Heads.draw(c, HEADS, t.uuid, x + 6, y + 6, 16);
            else if (t.icon != null) t.icon.draw(c, x + 7, y + 7, Theme.ACCENT, 2);
            int tx = x + 28, max = TOAST_W - 34;
            c.text(Theme.ellipsize(c, t.title, max), tx, y + 6, Theme.TEXT, false);
            c.text(Theme.ellipsize(c, t.body, max), tx, y + 16, Theme.TEXT_MUTED, false);
            y += TOAST_H + 3;
        }
    }
}
