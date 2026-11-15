package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.ItemTally;
import dev.dusk.client.module.setting.TextSetting;
import net.minecraft.item.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Counts whatever items you list: golden apples, pearls, TNT, fireballs.
 * Ids are typed comma-separated, with or without the "minecraft:" prefix.
 */
public class ItemCounter extends ItemTally {
    private final TextSetting ids = add(new TextSetting("items", "Items (ids, comma-separated)",
            "golden_apple, ender_pearl, tnt, fire_charge, arrow"));

    private String parsedFrom;
    private List<Item> parsed = Collections.emptyList();

    public ItemCounter() {
        super("itemcounter", "Item Counter", "Counts the items you choose across your whole inventory.", false);
        setPosition(80, 60);
    }

    @Override
    protected List<Item> items() {
        String raw = ids.get();
        if (!raw.equals(parsedFrom)) {
            parsedFrom = raw;
            parsed = parse(raw);
        }
        return parsed;
    }

    private static List<Item> parse(String raw) {
        List<Item> out = new ArrayList<Item>();
        for (String part : raw.split(",")) {
            String id = part.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (id.isEmpty()) continue;
            Item item = Item.getByNameOrId(id.indexOf(':') < 0 ? "minecraft:" + id : id);
            if (item != null && !out.contains(item)) out.add(item);
        }
        return out;
    }
}
