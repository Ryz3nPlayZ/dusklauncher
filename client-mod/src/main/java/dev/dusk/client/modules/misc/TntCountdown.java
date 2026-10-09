package dev.dusk.client.modules.misc;

import dev.dusk.client.module.Module;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.item.PrimedTnt;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Flex-HUD's TNT countdown: names nearby primed TNT with its remaining fuse.
 * Client-side only — the name is set on our copy of the entity.
 */
public class TntCountdown extends Module {
    private static final double RANGE = 20;

    /** TNT this module has named, so a fuse that leaves range (or the module going off) doesn't leave a frozen number behind */
    private final Set<PrimedTnt> named = Collections.newSetFromMap(new WeakHashMap<>());

    public TntCountdown() {
        super("tntcountdown", "TNT Countdown", Category.MISC,
                "Shows the remaining fuse above nearby primed TNT.");
    }

    @Override
    public void tick() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        List<PrimedTnt> tntEntities = player.level()
                .getEntitiesOfClass(PrimedTnt.class, player.getBoundingBox().inflate(RANGE), entity -> true);

        Set<PrimedTnt> near = named.isEmpty() ? Set.of() : new HashSet<>(tntEntities);
        named.removeIf(tnt -> {
            if (near.contains(tnt)) return false;
            unname(tnt);
            return true;
        });
        for (PrimedTnt tnt : tntEntities) {
            int seconds = tnt.getFuse() / 20;
            int hundredth = (tnt.getFuse() % 20) * 5;

            MutableComponent text = Component.literal(seconds + (hundredth < 10 ? ".0" : ".") + hundredth);
            switch (seconds) {
                case 2 -> text.withStyle(ChatFormatting.YELLOW);
                case 1 -> text.withStyle(ChatFormatting.GOLD);
                case 0 -> text.withStyle(ChatFormatting.RED);
                default -> text.withStyle(ChatFormatting.WHITE);
            }
            tnt.setCustomName(text);
            if (!tnt.isCustomNameVisible()) tnt.setCustomNameVisible(true);
            named.add(tnt);
        }
    }

    @Override
    protected void onDisable() {
        named.forEach(TntCountdown::unname);
        named.clear();
    }

    private static void unname(PrimedTnt tnt) {
        tnt.setCustomName(null);
        tnt.setCustomNameVisible(false);
    }
}
