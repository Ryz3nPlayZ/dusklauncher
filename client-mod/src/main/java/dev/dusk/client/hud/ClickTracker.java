package dev.dusk.client.hud;

import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;

/**
 * Frame-resolution edge detection on the attack/use keys, shared by the
 * CPS, keystrokes, reach and combo modules. Polled from the HUD hook every
 * frame (ticks are too coarse for click counting).
 */
public final class ClickTracker {
    private static final ArrayDeque<Long> LEFT = new ArrayDeque<>();
    private static final ArrayDeque<Long> RIGHT = new ArrayDeque<>();
    private static boolean leftDown, rightDown;
    private static int attackSerial;

    private ClickTracker() {}

    public static void update(Minecraft mc) {
        long now = System.currentTimeMillis();
        boolean l = mc.options.keyAttack.isDown();
        boolean r = mc.options.keyUse.isDown();
        if (l && !leftDown) {
            LEFT.addLast(now);
            attackSerial++;
        }
        if (r && !rightDown) RIGHT.addLast(now);
        leftDown = l;
        rightDown = r;
        prune(LEFT, now);
        prune(RIGHT, now);
    }

    private static void prune(ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > 1000) q.pollFirst();
    }

    public static int leftCps() { return LEFT.size(); }

    public static int rightCps() { return RIGHT.size(); }

    public static boolean leftDown() { return leftDown; }

    public static boolean rightDown() { return rightDown; }

    /** Increments once per attack press; modules diff it to notice new clicks. */
    public static int attackSerial() { return attackSerial; }
}
