package dev.dusk.client.gui;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

/** The vanilla item each module's card shows as its icon; modules without one get a category glyph. */
final class ModuleIcons {
    private ModuleIcons() {}

    private static Map<String, ItemStack> icons;

    static ItemStack of(String id) {
        if (icons == null) icons = build();
        return icons.getOrDefault(id, ItemStack.EMPTY);
    }

    private static Map<String, ItemStack> build() {
        Map<String, Item> m = new HashMap<>();
        m.put("armor", Items.IRON_CHESTPLATE);
        m.put("behindyou", Items.ENDER_EYE);
        m.put("biome", Items.GRASS_BLOCK);
        m.put("boatmap", Items.OAK_BOAT);
        m.put("clock", Items.CLOCK);
        m.put("colorsaturation", Items.AMETHYST_SHARD);
        m.put("combo", Items.IRON_SWORD);
        m.put("compass", Items.COMPASS);
        m.put("coords", Items.MAP);
        m.put("cps", Items.STONE_BUTTON);
        m.put("crosshair", Items.TARGET);
        m.put("damagetint", Items.REDSTONE);
        m.put("day", Items.CAKE);
        m.put("direction", Items.RECOVERY_COMPASS);
        m.put("distance", Items.SPYGLASS);
        m.put("effects", Items.POTION);
        m.put("entities", Items.ZOMBIE_HEAD);
        m.put("fogcontrol", Items.COBWEB);
        m.put("fps", Items.REPEATER);
        m.put("fullbright", Items.GLOWSTONE);
        m.put("fullinventory", Items.CHEST);
        m.put("gamemodeswitcher", Items.COMMAND_BLOCK);
        m.put("gametime", Items.DAYLIGHT_DETECTOR);
        m.put("helditem", Items.ITEM_FRAME);
        m.put("hitbox", Items.GLASS);
        m.put("inventorydisplay", Items.ENDER_CHEST);
        m.put("itemscale", Items.GOLDEN_APPLE);
        m.put("keystrokes", Items.LEVER);
        m.put("light", Items.LANTERN);
        m.put("lowfire", Items.FLINT_AND_STEEL);
        m.put("lowshield", Items.SHIELD);
        m.put("memory", Items.COMPARATOR);
        m.put("motion_blur", Items.PHANTOM_MEMBRANE);
        m.put("nametags", Items.NAME_TAG);
        m.put("nethercoords", Items.OBSIDIAN);
        m.put("nonightvision", Items.FERMENTED_SPIDER_EYE);
        m.put("nopumpkinblur", Items.CARVED_PUMPKIN);
        m.put("particles", Items.BLAZE_POWDER);
        m.put("ping", Items.BELL);
        m.put("pitchdisplay", Items.LADDER);
        m.put("playtime", Items.EXPERIENCE_BOTTLE);
        m.put("reach", Items.FISHING_ROD);
        m.put("riptideshieldfix", Items.TRIDENT);
        m.put("rotation", Items.ARROW);
        m.put("server", Items.BEACON);
        m.put("shield", Items.SHIELD);
        m.put("signreader", Items.OAK_SIGN);
        m.put("sneakstatus", Items.LEATHER_BOOTS);
        m.put("speed", Items.SUGAR);
        m.put("sprintstatus", Items.FEATHER);
        m.put("timechanger", Items.SUNFLOWER);
        m.put("tntcountdown", Items.TNT);
        m.put("togglesprint", Items.RABBIT_FOOT);
        m.put("tps", Items.OBSERVER);
        m.put("weather", Items.WATER_BUCKET);
        m.put("weatherchanger", Items.SNOWBALL);
        Map<String, ItemStack> out = new HashMap<>();
        m.forEach((id, item) -> out.put(id, new ItemStack(item)));
        return out;
    }
}
