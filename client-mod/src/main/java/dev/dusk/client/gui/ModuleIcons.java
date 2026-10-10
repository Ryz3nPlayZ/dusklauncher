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
        m.put("behindyou", Items.CREEPER_HEAD);
        m.put("biome", Items.GRASS_BLOCK);
        m.put("boatmap", Items.OAK_BOAT);
        m.put("capephysics", Items.ELYTRA);
        m.put("chattimestamps", Items.PAPER);
        m.put("chatmentions", Items.GOAT_HORN);
        m.put("chatheads", Items.SKELETON_SKULL);
        m.put("chatmacros", Items.WRITABLE_BOOK);
        m.put("clock", Items.CLOCK);
        m.put("stopwatch", Items.SCULK_SENSOR);
        m.put("resourcepacks", Items.PAINTING);
        m.put("scoreboard", Items.LECTERN);
        m.put("bossbar", Items.DRAGON_HEAD);
        m.put("tabping", Items.REDSTONE_TORCH);
        m.put("blockoutline", Items.TINTED_GLASS);
        m.put("backgroundfps", Items.BLUE_ICE);
        m.put("soundchanger", Items.JUKEBOX);
        m.put("fovchanger", Items.GLASS_PANE);
        m.put("totems", Items.TOTEM_OF_UNDYING);
        m.put("itemcounter", Items.BUNDLE);
        m.put("itempickups", Items.HOPPER);
        m.put("scrolltooltips", Items.OAK_HANGING_SIGN);
        m.put("scrolltransfer", Items.CHEST_MINECART);
        m.put("itemphysics", Items.DIAMOND);
        m.put("chatsearch", Items.BRUSH);
        m.put("customskies", Items.END_CRYSTAL);
        m.put("lightoverlay", Items.TORCH);
        m.put("bedwarsresources", Items.EMERALD);
        m.put("skyblockstats", Items.NETHER_STAR);
        m.put("hypixel", Items.GOLDEN_SWORD);
        m.put("slotlock", Items.TRIPWIRE_HOOK);
        m.put("namehider", Items.LEATHER_HELMET);
        m.put("colorsaturation", Items.AMETHYST_SHARD);
        m.put("combo", Items.IRON_SWORD);
        m.put("compactchat", Items.BOOKSHELF);
        m.put("compass", Items.COMPASS);
        m.put("minimap", Items.FILLED_MAP);
        m.put("confirmdisconnect", Items.BARRIER);
        m.put("statistics", Items.KNOWLEDGE_BOOK);
        m.put("coords", Items.MAP);
        m.put("cps", Items.STONE_BUTTON);
        m.put("crosshair", Items.TARGET);
        m.put("crosshairindicator", Items.SPYGLASS);
        m.put("damagetint", Items.REDSTONE);
        m.put("day", Items.CAKE);
        m.put("distance", Items.LEAD);
        m.put("effects", Items.POTION);
        m.put("entities", Items.ZOMBIE_HEAD);
        m.put("fogcontrol", Items.COBWEB);
        m.put("fps", Items.REPEATER);
        m.put("freecam", Items.ENDER_EYE);
        m.put("freelook", Items.ENDER_PEARL);
        m.put("fullbright", Items.GLOWSTONE);
        m.put("fullinventory", Items.CHEST);
        m.put("gamemodeswitcher", Items.COMMAND_BLOCK);
        m.put("gametime", Items.DAYLIGHT_DETECTOR);
        m.put("helditem", Items.ITEM_FRAME);
        m.put("hitbox", Items.GLASS);
        m.put("hungerinfo", Items.GOLDEN_CARROT);
        m.put("inventorydisplay", Items.ENDER_CHEST);
        m.put("itemscale", Items.GOLDEN_APPLE);
        m.put("keystrokes", Items.LEVER);
        m.put("light", Items.LANTERN);
        m.put("lowfire", Items.FLINT_AND_STEEL);
        m.put("totempop", Items.FIREWORK_STAR);
        m.put("glintcolor", Items.ENCHANTED_BOOK);
        m.put("cooldowns", Items.CHORUS_FRUIT);
        m.put("lowshield", Items.IRON_BARS);
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
        m.put("server", Items.BEACON);
        m.put("shieldstatuses", Items.SHIELD);
        m.put("signreader", Items.OAK_SIGN);
        m.put("lookingat", Items.GLOW_ITEM_FRAME);
        m.put("autoreconnect", Items.RECOVERY_COMPASS);
        m.put("serverlag", Items.SOUL_SAND);
        m.put("containerpreview", Items.SHULKER_BOX);
        m.put("chathistory", Items.BOOK);
        m.put("chatcopy", Items.WRITTEN_BOOK);
        m.put("lowdurability", Items.ANVIL);
        m.put("skinlayers3d", Items.PLAYER_HEAD);
        m.put("sneakstatus", Items.LEATHER_BOOTS);
        m.put("speed", Items.SUGAR);
        m.put("sprintstatus", Items.FEATHER);
        m.put("timechanger", Items.SUNFLOWER);
        m.put("tntcountdown", Items.TNT);
        m.put("togglesprint", Items.RABBIT_FOOT);
        m.put("tps", Items.OBSERVER);
        m.put("waypoints", Items.LODESTONE);
        m.put("weather", Items.WATER_BUCKET);
        m.put("weatherchanger", Items.SNOWBALL);
        m.put("zoom", Items.SPYGLASS);
        Map<String, ItemStack> out = new HashMap<>();
        m.forEach((id, item) -> out.put(id, new ItemStack(item)));
        return out;
    }
}
