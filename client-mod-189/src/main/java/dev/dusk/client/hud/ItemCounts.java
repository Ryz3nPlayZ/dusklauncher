package dev.dusk.client.hud;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** Inventory tallies and durability numbers. */
public final class ItemCounts {
    private ItemCounts() {}

    /** How many of {@code stack} the inventory holds; potions only count the same kind. */
    public static int stackCount(ItemStack stack, InventoryPlayer inv) {
        boolean byMeta = stack.getHasSubtypes();
        int count = 0;
        for (ItemStack s : inv.mainInventory) {
            if (s == null || s.getItem() != stack.getItem()) continue;
            if (byMeta && s.getMetadata() != stack.getMetadata()) continue;
            count += s.stackSize;
        }
        return count;
    }

    public static int itemCount(Item item, InventoryPlayer inv) {
        int count = 0;
        for (ItemStack s : inv.mainInventory) if (s != null && s.getItem() == item) count += s.stackSize;
        return count;
    }

    public static boolean hasDurability(ItemStack stack) {
        return stack != null && stack.isItemStackDamageable();
    }

    public static int durability(ItemStack stack) {
        return stack.getMaxDamage() - stack.getItemDamage();
    }

    public static int durabilityPercent(ItemStack stack) {
        return (int) Math.round((double) durability(stack) / stack.getMaxDamage() * 100);
    }

    /** Green to red, as the modern durability bar colours it. */
    public static int barColor(ItemStack stack) {
        float f = Math.max(0f, (float) durability(stack) / stack.getMaxDamage());
        return 0xFF000000 | java.awt.Color.HSBtoRGB(f / 3f, 1f, 1f);
    }
}
