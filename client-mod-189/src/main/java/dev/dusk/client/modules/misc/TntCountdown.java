package dev.dusk.client.modules.misc;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.util.EnumChatFormatting;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Flex-HUD's TNT countdown (behaviour): names nearby primed TNT with its
 * remaining fuse. Client-side only — the name is set on our copy of the
 * entity, and anything the server already named it is put back when we stop.
 */
public class TntCountdown extends Module {
    private static final double RANGE = 20;

    /** TNT this module has named, mapped to the name it had before us. */
    private final Map<EntityTNTPrimed, String> named = new HashMap<EntityTNTPrimed, String>();

    public TntCountdown() {
        super("tntcountdown", "TNT Countdown", Category.MISC,
                "Shows the remaining fuse above nearby primed TNT.");
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            releaseAll();
            return;
        }

        Iterator<Map.Entry<EntityTNTPrimed, String>> it = named.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<EntityTNTPrimed, String> e = it.next();
            EntityTNTPrimed tnt = e.getKey();
            boolean near = !tnt.isDead && mc.thePlayer.getDistanceSqToEntity(tnt) <= RANGE * RANGE;
            if (near && tnt.fuse > 0) continue;
            restore(tnt, e.getValue());
            it.remove();
        }

        for (Entity entity : mc.theWorld.getLoadedEntityList()) {
            if (!(entity instanceof EntityTNTPrimed)) continue;
            EntityTNTPrimed tnt = (EntityTNTPrimed) entity;
            if (tnt.isDead || tnt.fuse <= 0 || named.containsKey(tnt)) continue;
            if (mc.thePlayer.getDistanceSqToEntity(tnt) > RANGE * RANGE) continue;

            int seconds = tnt.fuse / 20;
            int hundredth = (tnt.fuse % 20) * 5;
            EnumChatFormatting color = seconds == 2 ? EnumChatFormatting.YELLOW
                    : seconds == 1 ? EnumChatFormatting.GOLD
                    : seconds == 0 ? EnumChatFormatting.RED : EnumChatFormatting.WHITE;
            String text = String.format(Locale.ROOT, "%s%d.%02d", color, seconds, hundredth);

            if (!named.containsKey(tnt)) named.put(tnt, tnt.hasCustomName() ? tnt.getCustomNameTag() : null);
            tnt.setCustomNameTag(text);
            tnt.setAlwaysRenderNameTag(true);
        }
    }

    @Override
    protected void onDisable() {
        releaseAll();
    }

    private void releaseAll() {
        for (Map.Entry<EntityTNTPrimed, String> e : named.entrySet()) restore(e.getKey(), e.getValue());
        named.clear();
    }

    private static void restore(EntityTNTPrimed tnt, String before) {
        if (tnt.isDead) return;
        if (before != null) tnt.setCustomNameTag(before);
        else {
            tnt.setCustomNameTag("");
            tnt.setAlwaysRenderNameTag(false);
        }
    }
}
