package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.ItemTally;
import dev.dusk.client.module.setting.TextSetting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Counts whatever items you list: golden apples, pearls, arrows, blocks.
 * Ids are typed comma-separated, with or without the "minecraft:" prefix.
 */
public class ItemCounter extends ItemTally {
    private final TextSetting ids = add(new TextSetting("items", "Items (ids, comma-separated)",
            "golden_apple, ender_pearl, arrow, experience_bottle", 200));

    private String parsedFrom;
    private List<Item> parsed = List.of();

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
        List<String> wanted = new ArrayList<>();
        for (String part : raw.split(",")) {
            String id = part.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (id.isEmpty()) continue;
            wanted.add(id.indexOf(':') < 0 ? "minecraft:" + id : id);
        }
        Item[] found = new Item[wanted.size()];
        // walk the registry once instead of building ids, which change type between versions
        for (Item item : BuiltInRegistries.ITEM) {
            int i = wanted.indexOf(String.valueOf(BuiltInRegistries.ITEM.getKey(item)));
            if (i >= 0) found[i] = item;
        }
        List<Item> out = new ArrayList<>(found.length);
        for (Item item : found) if (item != null && !out.contains(item)) out.add(item);
        return out;
    }
}
