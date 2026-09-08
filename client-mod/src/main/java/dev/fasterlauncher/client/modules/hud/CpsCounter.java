package dev.fasterlauncher.client.modules.hud;

import dev.fasterlauncher.client.module.Module;

import java.util.ArrayDeque;
import java.util.Deque;

/** Left/right clicks-per-second counter over a rolling 1s window. */
public class CpsCounter extends Module {
    private static final long WINDOW_MS = 1000;
    private final Deque<Long> leftClicks = new ArrayDeque<>();
    private final Deque<Long> rightClicks = new ArrayDeque<>();

    public CpsCounter() {
        super("cps", "CPS Counter", Category.HUD);
        setPosition(5, 80);
    }

    public void recordLeft() { record(leftClicks); }
    public void recordRight() { record(rightClicks); }

    private void record(Deque<Long> deque) {
        long now = System.currentTimeMillis();
        deque.addLast(now);
        while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) deque.pollFirst();
    }

    public int leftCps() { return count(leftClicks); }
    public int rightCps() { return count(rightClicks); }

    private int count(Deque<Long> deque) {
        long now = System.currentTimeMillis();
        deque.removeIf(t -> now - t > WINDOW_MS);
        return deque.size();
    }
}
