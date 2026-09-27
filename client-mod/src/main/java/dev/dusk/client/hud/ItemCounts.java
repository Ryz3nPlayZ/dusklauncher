package dev.dusk.client.hud;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Objects;

/** Inventory tallies and durability numbers, counted the way Flex-HUD counts them. */
public final class ItemCounts {
    private ItemCounts() {}

    /**
     * How many of {@code stack} the inventory holds. Potions, tipped arrows,
     * fireworks, books and the like only count copies with the same contents.
     */
    public static int stackCount(ItemStack stack, Inventory inv) {
        DataComponentType<?> key = null;
        if (stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION) || stack.is(Items.TIPPED_ARROW)) {
            key = DataComponents.POTION_CONTENTS;
        } else if (stack.is(Items.OMINOUS_BOTTLE)) {
            key = DataComponents.OMINOUS_BOTTLE_AMPLIFIER;
        } else if (stack.is(Items.FIREWORK_ROCKET)) {
            key = DataComponents.FIREWORKS;
        } else if (stack.is(Items.ENCHANTED_BOOK)) {
            key = DataComponents.STORED_ENCHANTMENTS;
        } else if (stack.is(Items.LIGHT)) {
            key = DataComponents.BLOCK_STATE;
        }
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(stack.getItem())) continue;
            if (key != null && !Objects.equals(s.getComponents().get(key), stack.getComponents().get(key))) continue;
            count += s.getCount();
        }
        return count;
    }

    public static int itemCount(Item item, Inventory inv) {
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) count += s.getCount();
        }
        return count;
    }

    /** Whether the item type has durability at all (unbreakable copies included). */
    public static boolean hasDurability(ItemStack stack) {
        return stack.getMaxDamage() > 0;
    }

    public static int durability(ItemStack stack) {
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    public static int durabilityPercent(ItemStack stack) {
        return (int) Math.round((double) durability(stack) / stack.getMaxDamage() * 100);
    }

    /** The durability bar's own colour, green to red. */
    public static int barColor(ItemStack stack) {
        return 0xFF000000 | stack.getBarColor();
    }
}
