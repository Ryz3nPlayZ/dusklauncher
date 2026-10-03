package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.Locale;

/** Flex-HUD's Entity Count: loaded entities around you, "Entities: 12 (radius 16)". */
public class EntityCount extends TextHud {
    private final IntSetting range = add(new IntSetting("range", "Radius", 16, 1, 512, 1, " blocks"));
    private final IntSetting yRange = add(new IntSetting("yRange", "Vertical radius", 16, 1, 512, 1, " blocks"));
    /** One choice rather than two switches, which together counted nothing. */
    private final ChoiceSetting count = add(new ChoiceSetting("count", "Count", "All", "All", "Mobs", "Items"));

    public EntityCount() {
        super("entities", "Entity Count", "How many entities are loaded around you.");
        setPosition(150, 60);
    }

    private String format(int n) {
        String prefix = count.is("Mobs") ? "Mobs" : count.is("Items") ? "Items" : "Entities";
        int r = range.get(), y = yRange.get();
        String radius = r == y ? String.format(Locale.ROOT, "radius %d", r) : String.format(Locale.ROOT, "r%d y%d", r, y);
        return prefix + ": " + n + " (" + radius + ")";
    }

    /** Entities only move once a tick, so the count is redone once a tick rather than every frame. */
    private long countedAt = Long.MIN_VALUE;
    private Object countedFor;
    private int countedWith;
    private String counted;

    @Override
    protected String text(HudContext ctx) {
        var level = ctx.level();
        var player = ctx.player();
        if (level == null || player == null) return null;
        long now = level.getGameTime();
        // the settings are part of the key so editing them while paused still updates
        int with = (count.index() * 1031 + range.get()) * 1031 + yRange.get();
        if (now == countedAt && countedFor == level && countedWith == with && counted != null) return counted;
        countedAt = now;
        countedFor = level;
        countedWith = with;
        return counted = tally(level, player);
    }

    private String tally(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.client.player.LocalPlayer player) {
        int n = 0;
        for (Entity e : level.entitiesForRendering()) {
            if (e == player) continue;
            if (count.is("Mobs") && !(e instanceof Mob)) continue;
            if (count.is("Items") && !(e instanceof ItemEntity)) continue;
            if (e.position().closerThan(player.position(), range.get(), yRange.get())) n++;
        }
        return format(n);
    }

    @Override
    protected String sample() {
        return format(10);
    }
}
