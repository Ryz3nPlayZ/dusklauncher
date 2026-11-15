package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.ItemTally;
import net.minecraft.init.Items;
import net.minecraft.item.Item;

import java.util.Arrays;
import java.util.List;

/** Bed Wars resources in your inventory: iron, gold, diamonds and emeralds. */
public class BedwarsResources extends ItemTally {
    private static final List<Item> RESOURCES = Arrays.asList(Items.iron_ingot, Items.gold_ingot, Items.diamond, Items.emerald);
    private static final int[] COLORS = {0xFFDDDDDD, 0xFFFFAA00, 0xFF55FFFF, 0xFF55FF55};

    public BedwarsResources() {
        super("bedwarsresources", "Bed Wars Resources", "Your iron, gold, diamonds and emeralds, for Bed Wars shops.", true);
        setPosition(10, 120);
    }

    @Override
    protected List<Item> items() { return RESOURCES; }

    @Override
    protected int countColor(Item item, int count) { return COLORS[RESOURCES.indexOf(item)]; }
}
