package dev.dusk.client.gui;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * The vanilla item each module's card shows as its icon; modules without one
 * get a category glyph. 1.8.9 has no 1.21 items (spyglass, lodestone...), so
 * the nearest 1.8.9 item stands in. Looked up by registry name so a missing
 * name just means no icon instead of a crash.
 */
final class ModuleIcons {
    private ModuleIcons() {}

    private static Map<String, ItemStack> icons;

    /** The module's icon, or null when it has none. */
    static ItemStack of(String id) {
        if (icons == null) icons = build();
        return icons.get(id);
    }

    private static final String[] TABLE = {
        "armor=iron_chestplate", "behindyou=skull:4", "biome=grass", "capephysics=feather",
        "chattimestamps=paper", "chatmentions=noteblock", "chatheads=skull:0", "chatmacros=writable_book",
        "clock=clock", "stopwatch=comparator", "resourcepacks=painting", "scoreboard=sign",
        "bossbar=skull:1", "tabping=redstone_torch", "blockhighlight=stained_glass:15", "backgroundfps=packed_ice",
        "lowinputlag=repeater", "soundchanger=jukebox", "fovchanger=glass_pane", "itemcounter=chest", "mousetweaks=hopper",
        "itempickups=hopper", "itemphysics=diamond", "chatsearch=compass", "customskies=ender_eye",
        "lightoverlay=torch", "bedwarsresources=emerald", "skyblockstats=nether_star", "hypixel=golden_sword",
        "slotlock=tripwire_hook", "namehider=leather_helmet", "colorsaturation=dye:5", "combo=iron_sword",
        "compactchat=bookshelf", "compass=compass", "minimap=filled_map", "confirmdisconnect=barrier",
        "statistics=enchanted_book", "coords=map", "cps=stone_button", "crosshair=bow",
        "crosshairindicator=arrow", "damagetint=redstone", "day=cake", "distance=lead",
        "effects=potion:8193", "entities=skull:2", "fogcontrol=web", "fps=repeater",
        "freecam=ender_eye", "freelook=ender_pearl", "fullbright=glowstone", "fullinventory=chest",
        "gametime=daylight_detector", "helditem=item_frame", "hitbox=glass", "hungerinfo=golden_carrot",
        "inventorydisplay=ender_chest", "itemscale=golden_apple", "keystrokes=lever", "light=redstone_lamp",
        "lowfire=flint_and_steel", "glintcolor=enchanted_book", "memory=comparator", "motion_blur=feather",
        "nametags=name_tag", "nethercoords=obsidian", "nonightvision=fermented_spider_eye", "nopumpkinblur=pumpkin",
        "particles=blaze_powder", "ping=noteblock", "pitchdisplay=ladder", "playtime=experience_bottle",
        "reach=fishing_rod", "server=beacon", "signreader=sign", "lookingat=item_frame",
        "autoreconnect=compass", "serverlag=soul_sand", "chathistory=book", "chatcopy=written_book",
        "lowdurability=anvil", "skinlayers3d=skull:3", "sneakstatus=leather_boots", "speed=sugar",
        "sprintstatus=feather", "timechanger=double_plant:0", "tntcountdown=tnt", "togglesprint=rabbit_foot",
        "tps=dispenser", "waypoints=beacon", "weather=water_bucket", "weatherchanger=snowball",
        "zoom=spider_eye",
    };

    private static Map<String, ItemStack> build() {
        Map<String, ItemStack> out = new HashMap<>();
        for (String row : TABLE) {
            int eq = row.indexOf('=');
            String id = row.substring(0, eq), spec = row.substring(eq + 1);
            int meta = 0, colon = spec.indexOf(':');
            if (colon >= 0) {
                meta = Integer.parseInt(spec.substring(colon + 1));
                spec = spec.substring(0, colon);
            }
            Item item = Item.getByNameOrId("minecraft:" + spec);
            if (item != null) out.put(id, new ItemStack(item, 1, meta));
        }
        return out;
    }
}
