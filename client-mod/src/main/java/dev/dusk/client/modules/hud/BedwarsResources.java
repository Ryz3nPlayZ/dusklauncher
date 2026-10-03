package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.ItemTally;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

/** Bed Wars resources in your inventory: iron, gold, diamonds and emeralds. */
public class BedwarsResources extends ItemTally {
    private static final List<Item> RESOURCES = List.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.EMERALD);
    private static final int[] COLORS = {0xFFDDDDDD, 0xFFFFAA00, 0xFF55FFFF, 0xFF55FF55};

    public BedwarsResources() {
        super("bedwarsresources", "Bed Wars Resources", "Your iron, gold, diamonds and emeralds, for Bed Wars shops.", true);
        setPosition(10, 120);
    }

    @Override
    protected List<Item> items() {
        return RESOURCES;
    }

    @Override
    protected int countColor(Item item, int count) {
        return COLORS[RESOURCES.indexOf(item)];
    }
}
