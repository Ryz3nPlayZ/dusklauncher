package dev.dusk.client.util;

import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;

/**
 * Clicks in the last second, shared by CPS, Keystrokes, Reach and Combo.
 * Mouse buttons are counted from the input queue (every press, however fast
 * the frame rate); keys bound to attack or use are polled each frame.
 */
public final class ClickTracker {
    private static final ArrayDeque<Long> LEFT = new ArrayDeque<Long>();
    private static final ArrayDeque<Long> RIGHT = new ArrayDeque<Long>();
    private static boolean leftDown, rightDown;
    private static int attackSerial;

    private ClickTracker() {}

    /** A mouse button changed state (from Forge's MouseEvent). */
    public static void onMouseButton(Minecraft mc, int button, boolean down) {
        int code = button - 100;
        if (code == mc.gameSettings.keyBindAttack.getKeyCode()) {
            if (down && !leftDown) left();
            leftDown = down;
        } else if (code == mc.gameSettings.keyBindUseItem.getKeyCode()) {
            if (down && !rightDown) RIGHT.addLast(System.currentTimeMillis());
            rightDown = down;
        }
    }

    /** Per frame: keyboard-bound attack/use keys, and dropping clicks older than a second. */
    public static void update(Minecraft mc) {
        int attack = mc.gameSettings.keyBindAttack.getKeyCode();
        int use = mc.gameSettings.keyBindUseItem.getKeyCode();
        if (attack > 0) {
            boolean l = Keys.isDown(attack) && mc.currentScreen == null;
            if (l && !leftDown) left();
            leftDown = l;
        } else if (mc.currentScreen != null || !Keys.isDown(attack)) {
            leftDown = false;
        }
        if (use > 0) {
            boolean r = Keys.isDown(use) && mc.currentScreen == null;
            if (r && !rightDown) RIGHT.addLast(System.currentTimeMillis());
            rightDown = r;
        } else if (mc.currentScreen != null || !Keys.isDown(use)) {
            rightDown = false;
        }
        long now = System.currentTimeMillis();
        prune(LEFT, now);
        prune(RIGHT, now);
    }

    private static void left() {
        LEFT.addLast(System.currentTimeMillis());
        attackSerial++;
    }

    private static void prune(ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > 1000) q.pollFirst();
    }

    public static int leftCps() { return LEFT.size(); }
    public static int rightCps() { return RIGHT.size(); }
    public static boolean leftDown() { return leftDown; }
    public static boolean rightDown() { return rightDown; }
    public static int attackSerial() { return attackSerial; }
}
