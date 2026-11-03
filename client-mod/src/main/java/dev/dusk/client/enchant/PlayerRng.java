package dev.dusk.client.enchant;

import java.util.ArrayList;
import java.util.List;

/**
 * The server's random for one player: a java.util.Random-style LCG with 48
 * bits of state. Every nextInt() or nextFloat() is one step of it. Enchanting
 * draws the next table seed as nextInt(), and throwing one item draws four
 * floats for its motion, so knowing the state tells you every seed to come
 * and how many throws reach each one.
 */
public final class PlayerRng {
    private PlayerRng() {}

    private static final long MULT = 0x5DEECE66DL, ADD = 0xBL, MASK = (1L << 48) - 1;
    /** Draws one thrown item takes (its random spin and push). */
    public static final int DRAWS_PER_THROW = 4;

    public static long step(long state) {
        return (state * MULT + ADD) & MASK;
    }

    /** nextInt() of the draw that left the random at {@code state}. */
    public static int output(long state) {
        return (int) (state >>> 16);
    }

    /** {@code state} moved on {@code draws} steps. */
    public static long skip(long state, int draws) {
        for (int i = 0; i < draws; i++) state = step(state);
        return state;
    }

    /** One way the random can be: its state after drawing the newer seed, and the draws between the two seeds. */
    public record Lock(long state, int gap) {}

    /**
     * The states the random can be in right after drawing {@code newer}, when
     * {@code older} was drawn at most {@code maxGap} steps before it. A seed
     * gives 32 of the 48 bits, so this tries the other 16.
     */
    public static List<Lock> crack(int older, int newer, int maxGap) {
        List<Lock> out = new ArrayList<>();
        long high = (older & 0xFFFFFFFFL) << 16;
        for (int low = 0; low < 1 << 16; low++) {
            long s = high | low;
            for (int gap = 1; gap <= maxGap; gap++) {
                s = step(s);
                if (output(s) == newer) out.add(new Lock(s, gap));
            }
        }
        return out;
    }
}
