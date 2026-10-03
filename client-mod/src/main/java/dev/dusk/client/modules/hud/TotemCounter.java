package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.ItemTally;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

/** How many Totems of Undying you carry, in red once you are down to none. */
public class TotemCounter extends ItemTally {
    private static final List<Item> TOTEM = List.of(Items.TOTEM_OF_UNDYING);

    public TotemCounter() {
        super("totems", "Totem Counter", "How many Totems of Undying you have left.", false);
        setPosition(80, 100);
    }

    @Override
    protected List<Item> items() {
        return TOTEM;
    }

    @Override
    protected int countColor(Item item, int count) {
        return count == 0 ? 0xFFFF5555 : textColor();
    }
}
