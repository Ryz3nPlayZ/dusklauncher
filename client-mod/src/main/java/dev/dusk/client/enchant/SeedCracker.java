package dev.dusk.client.enchant;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Recovers the table's full seed. The server only sends its low 16 bits, so
 * this tries all 65,536 high halves (and every bookshelf count when the table
 * isn't known) and keeps the seeds whose costs and hints match what the table
 * shows. Another item in the table narrows a short list further.
 */
public final class SeedCracker {
    private SeedCracker() {}

    /** Past this many fits a reading says too little to keep them all. */
    private static final int MAX_FITS = 4096;

    /** What the table shows for one item. {@code shelves} is -1 when the table's position is unknown. */
    public record Reading(int low16, ItemStack stack, int shelves, int[] costs, int[] clues, int[] levels) {}

    /**
     * A seed that fits. {@code state} is the player's random right after it was
     * drawn, or -1 when the random isn't known; {@code gap} is how many draws
     * it took from the previous seed.
     */
    public record Fit(int seed, int shelves, long state, int gap) {}

    /** The seeds among {@code from} (or among all of them, when null) that fit {@code r}. */
    public static List<Fit> crack(EnchantPredictor p, Reading r, @Nullable List<Fit> from) {
        List<Fit> out = new ArrayList<>();
        int lo = r.shelves() >= 0 ? r.shelves() : 0, hi = r.shelves() >= 0 ? r.shelves() : 15;
        if (from != null) {
            for (Fit f : from) {
                for (int s = lo; s <= hi; s++) {
                    if (fits(p, f.seed(), s, r)) {
                        out.add(new Fit(f.seed(), s, f.state(), f.gap()));
                        break;
                    }
                }
            }
            return out;
        }
        for (int high = 0; high < 1 << 16; high++) {
            int seed = high << 16 | r.low16();
            for (int s = lo; s <= hi; s++) {
                if (!fits(p, seed, s, r)) continue;
                out.add(new Fit(seed, s, -1, 0));
                if (out.size() >= MAX_FITS) return out;
            }
        }
        return out;
    }

    private static boolean fits(EnchantPredictor p, int seed, int shelves, Reading r) {
        return p.costsMatch(seed, shelves, r.stack(), r.costs())
                && EnchantPredictor.shows(p.predict(seed, shelves, r.stack()), r.costs(), r.clues(), r.levels());
    }
}
