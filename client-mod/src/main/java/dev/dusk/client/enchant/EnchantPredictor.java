package dev.dusk.client.enchant;

import dev.dusk.client.compat.EnchantCompat;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * What an enchanting table offers for a seed, a bookshelf count and an item:
 * the same steps as the server's EnchantmentMenu, calling vanilla's own
 * EnchantmentHelper so the numbers follow whichever version is running.
 * One instance per thread (it owns a random).
 */
public final class EnchantPredictor {
    /** One of the table's three buttons. {@code result} is everything the item would get. */
    public record Offer(int cost, int clueId, int clueLevel, List<EnchantmentInstance> result) {
        static final Offer NONE = new Offer(0, -1, -1, List.of());
    }

    private final List<Holder<Enchantment>> table;
    private final IdMap<Holder<Enchantment>> ids;
    private final RandomSource random = RandomSource.create(0L);

    public EnchantPredictor(RegistryAccess access) {
        Registry<Enchantment> registry = EnchantCompat.registry(access);
        List<Holder<Enchantment>> list = new ArrayList<>();
        registry.getTagOrEmpty(EnchantmentTags.IN_ENCHANTING_TABLE).forEach(list::add);
        this.table = List.copyOf(list);
        this.ids = registry.asHolderIdMap();
    }

    private EnchantPredictor(EnchantPredictor from) {
        this.table = from.table;
        this.ids = from.ids;
    }

    /** Same registry view, own random: for another thread. */
    public EnchantPredictor fork() {
        return new EnchantPredictor(this);
    }

    /** The table's enchantments that {@code stack} can roll (all of them for a book). */
    public List<Holder<Enchantment>> rollable(ItemStack stack) {
        boolean book = stack.is(Items.BOOK);
        List<Holder<Enchantment>> out = new ArrayList<>();
        for (Holder<Enchantment> h : table) if (book || h.value().isPrimaryItem(stack)) out.add(h);
        return out;
    }

    public int id(Holder<Enchantment> enchantment) {
        return ids.getId(enchantment);
    }

    /** Whether the three costs come out as {@code costs}: the cheap first check for a candidate seed. */
    public boolean costsMatch(int seed, int shelves, ItemStack stack, int[] costs) {
        random.setSeed(seed);
        for (int i = 0; i < 3; i++) {
            int c = EnchantmentHelper.getEnchantmentCost(random, i, shelves, stack);
            if (c < i + 1) c = 0;
            if (c != costs[i]) return false;
        }
        return true;
    }

    public Offer[] predict(int seed, int shelves, ItemStack stack) {
        int[] costs = new int[3];
        random.setSeed(seed);
        for (int i = 0; i < 3; i++) {
            int c = EnchantmentHelper.getEnchantmentCost(random, i, shelves, stack);
            costs[i] = c < i + 1 ? 0 : c;
        }
        Offer[] out = new Offer[3];
        for (int i = 0; i < 3; i++) {
            if (costs[i] <= 0) {
                out[i] = Offer.NONE;
                continue;
            }
            List<EnchantmentInstance> list = roll(seed, i, costs[i], stack);
            if (list.isEmpty()) {
                out[i] = new Offer(costs[i], -1, -1, List.of());
                continue;
            }
            // the hint: one entry of the list, picked with the random right where the roll left it
            EnchantmentInstance hint = list.get(random.nextInt(list.size()));
            out[i] = new Offer(costs[i], ids.getId(EnchantCompat.enchantment(hint)), EnchantCompat.level(hint), list);
        }
        return out;
    }

    /** Whether the table would show exactly these costs and hints. */
    public static boolean shows(Offer[] offers, int[] costs, int[] clues, int[] levels) {
        for (int i = 0; i < 3; i++) {
            Offer o = offers[i];
            if (o.cost() != costs[i]) return false;
            if (o.cost() > 0 && (o.clueId() != clues[i] || o.clueLevel() != levels[i])) return false;
        }
        return true;
    }

    private List<EnchantmentInstance> roll(int seed, int slot, int cost, ItemStack stack) {
        random.setSeed(seed + slot); // int sum, as vanilla adds before widening
        List<EnchantmentInstance> list = new ArrayList<>(EnchantmentHelper.selectEnchantment(random, stack, cost, table.stream()));
        if (stack.is(Items.BOOK) && list.size() > 1) list.remove(random.nextInt(list.size()));
        return list;
    }
}
