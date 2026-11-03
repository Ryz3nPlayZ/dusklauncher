package dev.dusk.client.modules.misc;

import dev.dusk.client.compat.Clicks;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.compat.EnchantCompat;
import dev.dusk.client.compat.ScreenWidgets;
import dev.dusk.client.enchant.EnchantPredictor;
import dev.dusk.client.enchant.EnchantPredictor.Offer;
import dev.dusk.client.enchant.PlayerRng;
import dev.dusk.client.enchant.SeedCracker;
import dev.dusk.client.enchant.SeedCracker.Fit;
import dev.dusk.client.enchant.SeedCracker.Reading;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.IntSetting;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enchant Oracle: reads the enchanting table's hidden seed, works out your
 * player's random from two seeds in a row, and from then on knows every
 * table roll to come. Pick the enchantments you want; it says how many
 * items to throw (each throw moves the random on four draws), throws them,
 * and lights up the button that gives exactly that.
 *
 * <p>The table only shows 16 of the seed's 32 bits, so the rest come from
 * matching costs and hints against every possible seed (see
 * {@link SeedCracker}). Everything is worked out on the client from what
 * the table already shows; the only things it sends are the item throws you
 * ask for.
 */
public class EnchantOracle extends Module {
    private static final int GUI_W = 176, GUI_H = 166;
    private static final int PANEL_W = 190, PANEL_MIN_W = 130, PAD = 4;
    private static final int GREEN = 0xFF55FF55, WHITE = 0xFFFFFFFF, GREY = 0xFFAAAAAA, GOLD = 0xFFFFC600, RED = 0xFFFF5555;
    /** Throws per tick: quick, without a burst of a hundred clicks at once. */
    private static final int THROWS_PER_TICK = 8;
    /** Draws allowed between two seeds when working the random out from them. */
    private static final int CRACK_GAP = 64;

    private static EnchantOracle instance;

    private final IntSetting maxThrows = add(new IntSetting("maxthrows", "Most items to throw", 256, 16, 2048, 16, ""));

    /** One enchantment wanted, at least this level. */
    public record Want(Holder<Enchantment> enchantment, int min) {}

    /** How to get the goal: throw until {@code throwsNeeded} since the last seed, enchant once, then take {@code slot}. */
    record Plan(int throwsNeeded, int slot, int cost, List<EnchantmentInstance> result, int seed, boolean direct) {}

    // ---- goal (kept while the world is) ----
    private final List<Want> wants = new ArrayList<>();
    private ItemStack target = ItemStack.EMPTY;

    // ---- what's known ----
    @Nullable private ClientLevel level;
    @Nullable private EnchantPredictor predictor;
    @Nullable private EnchantmentMenu menu;
    private int shelves = -1;
    private int lowSeen = -1;
    @Nullable private Fit known;
    @Nullable private List<Fit> candidates;
    @Nullable private Integer previousSeed;
    /** The player's random right after its last seed draw, or -1 while not known. */
    private long rng = -1;
    /** Items thrown since that draw, and how many had been when the table last drew. */
    private int thrown, thrownBefore;
    @Nullable private Plan plan;
    private boolean noPlan;
    private String note = "";
    private boolean failed;

    // ---- reading the table ----
    private int[] lastReading;
    private int stableTicks;
    private int[] processed;
    @Nullable private Offer[] offers;

    // ---- work off the game thread ----
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Dusk Enchant Oracle");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<>();
    private int generation;
    private boolean cracking, planning;

    private int toThrow;

    @Nullable private static Button goalButton, throwButton;

    public EnchantOracle() {
        super("enchantoracle", "Enchant Oracle", Category.MISC,
                "Reads the enchanting table's seed and your random, then tells you how many items to throw to get the enchantments you pick.");
        instance = this;
    }

    @Nullable
    static EnchantOracle active() {
        EnchantOracle m = instance;
        return m != null && m.enabled() ? m : null;
    }

    // ---- goal, for the goal screen ----

    List<Want> wants() { return wants; }

    ItemStack target() { return target; }

    @Nullable
    EnchantPredictor predictor() { return predictor; }

    void setGoal(ItemStack stack, List<Want> picked) {
        target = stack.copyWithCount(1);
        wants.clear();
        wants.addAll(picked);
        replan();
    }

    void clearGoal() {
        wants.clear();
        target = ItemStack.EMPTY;
        replan();
    }

    private void replan() {
        plan = null;
        noPlan = false;
        generation++;
        planning = false;
    }

    // ---- reset ----

    private void forgetSeed() {
        known = null;
        candidates = null;
        previousSeed = null;
        rng = -1;
        thrown = 0;
        toThrow = 0;
        offers = null;
        processed = null;
        lastReading = null;
        lowSeen = -1;
        failed = false;
        note = "";
        replan();
        cracking = false;
    }

    @Override
    protected void onDisable() {
        forgetSeed();
        menu = null;
    }

    // ---- every tick ----

    @Override
    public void tick() {
        Runnable r;
        while ((r = results.poll()) != null) r.run();

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null) {
            if (level != null) {
                forgetSeed();
                wants.clear();
                target = ItemStack.EMPTY;
                level = null;
                predictor = null;
            }
            return;
        }
        if (mc.level != level) {
            // another world or dimension: the server may have a new player object, with a new random
            forgetSeed();
            level = mc.level;
            predictor = new EnchantPredictor(mc.level.registryAccess());
        }
        if (!(player.containerMenu instanceof EnchantmentMenu m)) {
            menu = null;
            return;
        }
        if (m != menu) {
            menu = m;
            shelves = countShelves(mc);
            lastReading = null;
            stableTicks = 0;
            if (known != null && shelves >= 0) known = new Fit(known.seed(), shelves, known.state(), known.gap());
        }

        int low = m.getEnchantmentSeed() & 0xFFFF;
        if (lowSeen >= 0 && low != lowSeen) onSeedChanged(low);
        lowSeen = low;

        throwSome(m, player);
        read(m, player);
        updateButtons(m, player);
    }

    private static int countShelves(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit)) return -1;
        BlockPos table = hit.getBlockPos();
        if (!mc.level.getBlockState(table).is(Blocks.ENCHANTING_TABLE)) return -1;
        int n = 0;
        for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
            if (EnchantingTableBlock.isValidBookShelf(mc.level, table, offset)) n++;
        }
        return Math.min(n, 15);
    }

    /** The table drew a new seed: someone enchanted. */
    private void onSeedChanged(int low) {
        Fit old = known;
        if (old != null && plan != null && old.seed() == plan.seed() && !target.isEmpty()) {
            wants.clear();
            target = ItemStack.EMPTY;
            note = "Enchanted. Set a goal for the next item.";
        }
        previousSeed = old != null ? Integer.valueOf(old.seed()) : null;
        known = null;
        candidates = null;
        offers = null;
        processed = null;
        failed = false;
        thrownBefore = thrown;
        thrown = 0;
        toThrow = 0;
        replan();
        cracking = false;

        if (rng == -1) return;
        // the random is known: the new seed is one of the next draws whose low half matches
        List<Fit> next = new ArrayList<>();
        long s = rng;
        int limit = PlayerRng.DRAWS_PER_THROW * maxThrows.get() + 1 + 256;
        for (int gap = 1; gap <= limit; gap++) {
            s = PlayerRng.step(s);
            int seed = PlayerRng.output(s);
            if ((seed & 0xFFFF) == low) next.add(new Fit(seed, old != null ? old.shelves() : Math.max(shelves, 0), s, gap));
        }
        rng = -1;
        if (next.size() == 1) {
            accept(next.get(0));
        } else if (!next.isEmpty()) {
            candidates = next;
        }
    }

    /** {@code fit} is the table's seed now. */
    private void accept(Fit fit) {
        known = fit;
        candidates = null;
        if (shelves < 0) shelves = fit.shelves();
        if (fit.state() != -1) {
            rng = fit.state();
            note = fit.gap() == PlayerRng.DRAWS_PER_THROW * thrownBefore + 1 ? ""
                    : "Your random moved more than the throws did (eating, getting hit, sprinting). Replanned.";
        }
    }

    /** Looks at what the table shows, checks or cracks the seed from it, and plans. */
    private void read(EnchantmentMenu m, LocalPlayer player) {
        ItemStack item = m.getSlot(0).getItem();
        int[] reading = new int[13];
        reading[0] = lowSeen;
        reading[1] = System.identityHashCode(item.getItem());
        reading[2] = item.isEmpty() ? 0 : 1;
        for (int i = 0; i < 3; i++) {
            reading[3 + i] = m.costs[i];
            reading[6 + i] = m.enchantClue[i];
            reading[9 + i] = m.levelClue[i];
        }
        reading[12] = shelves;
        stableTicks = Arrays.equals(reading, lastReading) ? stableTicks + 1 : 0;
        lastReading = reading;

        boolean showsCosts = !item.isEmpty() && (m.costs[0] > 0 || m.costs[1] > 0 || m.costs[2] > 0);
        if (predictor == null) return;

        if (known != null) {
            offers = !item.isEmpty() ? predictor.predict(known.seed(), known.shelves(), item) : null;
            if (showsCosts && stableTicks >= 2 && offers != null
                    && !EnchantPredictor.shows(offers, m.costs, m.enchantClue, m.levelClue)) {
                recheck(item, m);
            }
        }

        if (known == null && showsCosts && stableTicks >= 2 && !cracking && !Arrays.equals(reading, processed)) {
            processed = reading;
            crack(new Reading(lowSeen, item.copy(), shelves, m.costs.clone(), m.enchantClue.clone(), m.levelClue.clone()));
        }

        if (known != null && rng != -1 && !wants.isEmpty() && plan == null && !noPlan && !planning) {
            startPlan(player);
        }
    }

    /** The table doesn't show what the known seed says: maybe other bookshelves; else start over. */
    private void recheck(ItemStack item, EnchantmentMenu m) {
        for (int s = 0; s <= 15; s++) {
            if (EnchantPredictor.shows(predictor.predict(known.seed(), s, item), m.costs, m.enchantClue, m.levelClue)) {
                known = new Fit(known.seed(), s, known.state(), known.gap());
                shelves = s;
                return;
            }
        }
        known = null;
        rng = -1;
        candidates = null;
        previousSeed = null;
        replan();
        note = "The table didn't match. Reading the seed again.";
    }

    private void crack(Reading reading) {
        EnchantPredictor p = predictor.fork();
        List<Fit> from = candidates;
        Integer before = previousSeed;
        int gen = ++generation;
        cracking = true;
        worker.execute(() -> {
            List<Fit> fits = SeedCracker.crack(p, reading, from);
            List<PlayerRng.Lock> locks = fits.size() == 1 && fits.get(0).state() == -1 && before != null
                    ? PlayerRng.crack(before, fits.get(0).seed(), CRACK_GAP) : List.of();
            results.add(() -> {
                if (gen != generation) return;
                cracking = false;
                if (fits.isEmpty()) {
                    if (from != null) {
                        candidates = null; // the list was wrong (something moved the random): from scratch
                        processed = null;
                    } else {
                        failed = true;
                    }
                    return;
                }
                if (fits.size() > 1) {
                    candidates = fits;
                    return;
                }
                accept(fits.get(0));
                if (rng == -1 && locks.size() == 1) {
                    rng = locks.get(0).state();
                    thrown = 0;
                    note = "";
                }
                previousSeed = null;
            });
        });
    }

    private void startPlan(LocalPlayer player) {
        EnchantPredictor p = predictor.fork();
        ItemStack goalItem = target.copy();
        List<Want> goal = List.copyOf(wants);
        Fit now = known;
        long state = rng;
        int from = thrown, max = maxThrows.get();
        int levels = player.getAbilities().instabuild ? Integer.MAX_VALUE : player.experienceLevel;
        int gen = ++generation;
        planning = true;
        worker.execute(() -> {
            Plan found = plan(p, goalItem, goal, now, state, from, max, levels);
            results.add(() -> {
                if (gen != generation) return;
                planning = false;
                plan = found;
                noPlan = found == null;
            });
        });
    }

    @Nullable
    private static Plan plan(EnchantPredictor p, ItemStack item, List<Want> goal, Fit now, long state, int from, int max, int levels) {
        Plan direct = best(p.predict(now.seed(), now.shelves(), item), goal, levels, 0, now.seed(), true);
        if (direct != null) return direct;
        long s = PlayerRng.skip(state, PlayerRng.DRAWS_PER_THROW * from);
        for (int n = from; n <= max; n++) {
            int seed = PlayerRng.output(PlayerRng.step(s));
            Plan found = best(p.predict(seed, now.shelves(), item), goal, levels, n, seed, false);
            if (found != null) return found;
            s = PlayerRng.skip(s, PlayerRng.DRAWS_PER_THROW);
        }
        return null;
    }

    /** The cheapest button among {@code offers} that gives the whole goal. */
    @Nullable
    private static Plan best(Offer[] offers, List<Want> goal, int levels, int throwsNeeded, int seed, boolean direct) {
        for (int i = 0; i < 3; i++) {
            Offer o = offers[i];
            if (o.cost() <= 0 || o.cost() > levels || !gives(o.result(), goal)) continue;
            return new Plan(throwsNeeded, i, o.cost(), o.result(), seed, direct);
        }
        return null;
    }

    private static boolean gives(List<EnchantmentInstance> result, List<Want> goal) {
        for (Want w : goal) {
            boolean got = false;
            for (EnchantmentInstance e : result) {
                if (EnchantCompat.enchantment(e).equals(w.enchantment()) && EnchantCompat.level(e) >= w.min()) {
                    got = true;
                    break;
                }
            }
            if (!got) return false;
        }
        return true;
    }

    // ---- throwing ----

    private int remainingThrows() {
        return plan == null || plan.direct() ? 0 : Math.max(0, plan.throwsNeeded() - thrown);
    }

    /** Whether {@code stack} is safe to throw away: plain stackable blocks (cobblestone, dirt), never lapis or the goal item. */
    private boolean junk(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem && stack.getMaxStackSize() > 1
                && stack.getRarity() == Rarity.COMMON && !stack.isEnchanted() && !stack.is(Items.LAPIS_BLOCK)
                && (target.isEmpty() || !stack.is(target.getItem()));
    }

    @Nullable
    private Slot junkSlot(EnchantmentMenu m) {
        SlotLock lock = SlotLock.active();
        Slot best = null;
        for (Slot s : m.slots) {
            if (!(s.container instanceof Inventory) || !junk(s.getItem())) continue;
            if (lock != null && lock.isLocked(s)) continue;
            if (best == null || s.getItem().getCount() > best.getItem().getCount()) best = s;
        }
        return best;
    }

    private int junkCount(EnchantmentMenu m) {
        SlotLock lock = SlotLock.active();
        int n = 0;
        for (Slot s : m.slots) {
            if (s.container instanceof Inventory && junk(s.getItem()) && (lock == null || !lock.isLocked(s))) {
                n += s.getItem().getCount();
            }
        }
        return n;
    }

    private void throwSome(EnchantmentMenu m, LocalPlayer player) {
        for (int i = 0; i < THROWS_PER_TICK && toThrow > 0; i++) {
            if (!m.getCarried().isEmpty()) return;
            Slot s = junkSlot(m);
            if (s == null) {
                toThrow = 0;
                return;
            }
            Clicks.throwOne(m.containerId, s.index, player);
            thrown++;
            toThrow--;
        }
    }

    // ---- buttons on the enchanting screen ----

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof EnchantmentScreen)) return;
            EnchantOracle m = active();
            if (m == null) return;
            int[] box = panel(w, h);
            if (box == null) return;
            int half = (box[2] - 2) / 2;
            goalButton = Button.builder(Component.literal("Set goal"), b -> m.openGoal(screen))
                    .bounds(box[0], box[1], half, 20).build();
            throwButton = Button.builder(Component.literal("Throw"), b -> m.toThrow = m.remainingThrows())
                    .bounds(box[0] + half + 2, box[1], box[2] - half - 2, 20).build();
            ScreenWidgets.of(screen).add(goalButton);
            ScreenWidgets.of(screen).add(throwButton);
            if (menuOf(client) != null) m.updateButtons(menuOf(client), client.player);
            ScreenEvents.remove(screen).register(s -> {
                goalButton = null;
                throwButton = null;
            });
        });
    }

    @Nullable
    private static EnchantmentMenu menuOf(Minecraft mc) {
        return mc.player != null && mc.player.containerMenu instanceof EnchantmentMenu m ? m : null;
    }

    private void openGoal(net.minecraft.client.gui.screens.Screen screen) {
        EnchantmentMenu m = menuOf(Minecraft.getInstance());
        if (m == null || predictor == null) return;
        ItemStack item = m.getSlot(0).getItem();
        Compat.setScreen(Minecraft.getInstance(), new OracleGoalScreen(screen, this, item.isEmpty() ? target : item));
    }

    private void updateButtons(EnchantmentMenu m, LocalPlayer player) {
        if (goalButton != null) {
            goalButton.active = !m.getSlot(0).getItem().isEmpty() || !target.isEmpty();
            goalButton.setMessage(Component.literal(wants.isEmpty() ? "Set goal" : "Change goal"));
        }
        if (throwButton != null) {
            int left = remainingThrows();
            int have = junkCount(m);
            String label;
            if (toThrow > 0) label = "Throwing, " + toThrow + " left";
            else if (left == 0) label = "Throw";
            else if (have < left) label = "Need " + left + " blocks";
            else label = "Throw " + left + " " + junkSlot(m).getItem().getHoverName().getString();
            throwButton.setMessage(Component.literal(label));
            throwButton.active = toThrow == 0 && left > 0 && have >= left;
        }
    }

    // ---- the panel beside the table ----

    /** x, y, width of the panel beside the table's window, or null when the window is too narrow. */
    @Nullable
    private static int[] panel(int screenW, int screenH) {
        int left = (screenW - GUI_W) / 2, top = (screenH - GUI_H) / 2;
        int x = left + GUI_W + PAD;
        int w = Math.min(PANEL_W, screenW - x - PAD);
        if (w < PANEL_MIN_W) {
            w = Math.min(PANEL_W, left - 2 * PAD);
            x = left - PAD - w;
        }
        return w < PANEL_MIN_W ? null : new int[] {x, Math.max(PAD, top), w};
    }

    /** Called after the enchanting screen draws. */
    public static void draw(Canvas c, int screenW, int screenH) {
        EnchantOracle m = active();
        EnchantmentMenu menu = menuOf(Minecraft.getInstance());
        if (m == null || menu == null) return;
        int[] box = panel(screenW, screenH);
        if (box == null) return;
        m.highlight(c, menu, screenW, screenH);

        List<Line> lines = m.lines(menu);
        int x = box[0], y = box[1] + 24, w = box[2];
        int lh = c.lineHeight() + 1;
        List<Line> wrapped = new ArrayList<>();
        for (Line l : lines) wrap(c, l, w - 8, wrapped);
        int h = wrapped.size() * lh + 8;
        c.fill(x, y, x + w, y + h, 0xD0101014);
        c.outline(x, y, w, h, 0xFF3A3A44);
        int ty = y + 4;
        for (Line l : wrapped) {
            c.text(l.text, x + 4, ty, l.argb, true);
            ty += lh;
        }
    }

    private void highlight(Canvas c, EnchantmentMenu menu, int screenW, int screenH) {
        if (!ready(menu)) return;
        int left = (screenW - GUI_W) / 2, top = (screenH - GUI_H) / 2;
        int y = top + 14 + 19 * plan.slot();
        c.outline(left + 59, y - 1, 110, 21, GREEN);
        c.outline(left + 60, y, 108, 19, GREEN);
    }

    /** The table shows the planned seed and the goal item is in it. */
    private boolean ready(EnchantmentMenu menu) {
        return plan != null && known != null && known.seed() == plan.seed()
                && !target.isEmpty() && menu.getSlot(0).getItem().is(target.getItem());
    }

    private record Line(String text, int argb) {}

    private static void wrap(Canvas c, Line l, int width, List<Line> out) {
        if (l.text.isEmpty()) {
            out.add(l);
            return;
        }
        StringBuilder row = new StringBuilder();
        for (String word : l.text.split(" ")) {
            String next = row.length() == 0 ? word : row + " " + word;
            if (row.length() > 0 && c.textWidth(next) > width) {
                out.add(new Line(row.toString(), l.argb));
                row = new StringBuilder(word);
            } else {
                row = new StringBuilder(next);
            }
        }
        out.add(new Line(row.toString(), l.argb));
    }

    private List<Line> lines(EnchantmentMenu menu) {
        List<Line> out = new ArrayList<>();
        out.add(new Line("Enchant Oracle", GOLD));
        ItemStack item = menu.getSlot(0).getItem();

        if (known == null) {
            if (failed) out.add(new Line("This table doesn't roll like vanilla, so its seed can't be read.", RED));
            else if (cracking) out.add(new Line("Reading the seed…", WHITE));
            else if (candidates != null) out.add(new Line(candidates.size() + " seeds fit. Swap in a different item to narrow it down.", WHITE));
            else if (previousSeed != null) out.add(new Line("Put an item in to read the new seed.", WHITE));
            else out.add(new Line("Put an item in the table to read its seed.", WHITE));
        } else if (rng == -1) {
            out.add(new Line("Seed read. Enchant anything once (a book on the top button is cheapest) to lock your random. It can take two.", WHITE));
        } else {
            out.add(new Line("Random locked: every roll from here is known.", GREEN));
        }
        if (!note.isEmpty()) out.add(new Line(note, GREY));

        if (offers != null && known != null) {
            out.add(new Line("", WHITE));
            for (int i = 0; i < 3; i++) {
                Offer o = offers[i];
                if (o.cost() <= 0) continue;
                out.add(new Line("Button " + (i + 1) + " (" + o.cost() + " levels): " + names(o.result()), WHITE));
            }
        }

        if (!wants.isEmpty()) {
            out.add(new Line("", WHITE));
            out.add(new Line("Goal: " + target.getHoverName().getString() + " with " + wantNames(), GOLD));
            if (rng == -1 || known == null) {
                out.add(new Line("Lock your random first.", GREY));
            } else if (planning) {
                out.add(new Line("Planning…", WHITE));
            } else if (noPlan) {
                out.add(new Line("Nothing within " + maxThrows.get() + " throws gives that at your level. Lower a level, drop an enchantment, add bookshelves or raise the throw limit.", RED));
            } else if (plan != null) {
                planLines(out, item);
            }
        }
        return out;
    }

    private void planLines(List<Line> out, ItemStack item) {
        String name = target.getHoverName().getString();
        String take = "take button " + (plan.slot() + 1) + " (" + plan.cost() + " levels)";
        if (known.seed() == plan.seed()) {
            out.add(new Line(item.is(target.getItem()) ? "Ready: " + take + "." : "Ready: put the " + name + " in and " + take + ".", GREEN));
        } else {
            int left = remainingThrows();
            out.add(new Line("1. Throw " + plan.throwsNeeded() + " blocks" + (left == 0 ? " (done)" : ", " + left + " to go (Throw button)") + ".",
                    left == 0 ? GREY : WHITE));
            out.add(new Line("2. Enchant any item to roll the new seed (a book on the top button is cheapest).", left == 0 ? WHITE : GREY));
            out.add(new Line("3. Put the " + name + " in and " + take + ".", GREY));
        }
        out.add(new Line("Gets " + names(plan.result()), GREEN));
    }

    private String wantNames() {
        StringBuilder sb = new StringBuilder();
        for (Want w : wants) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Enchantment.getFullname(w.enchantment(), w.min()).getString());
            if (w.min() < w.enchantment().value().getMaxLevel()) sb.append('+');
        }
        return sb.toString();
    }

    private static String names(List<EnchantmentInstance> list) {
        if (list.isEmpty()) return "nothing";
        StringBuilder sb = new StringBuilder();
        for (EnchantmentInstance e : list) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Enchantment.getFullname(EnchantCompat.enchantment(e), EnchantCompat.level(e)).getString());
        }
        return sb.toString();
    }
}
