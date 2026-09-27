package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.Locale;

/** Flex-HUD's Entity Count: loaded entities around you, "Entity count: 12 (radius 16)". */
public class EntityCount extends TextHud {
    private final IntSetting range = add(new IntSetting("range", "Radius", 16, 0, 512, 1, " blocks"));
    private final IntSetting yRange = add(new IntSetting("yRange", "Vertical radius", 16, 0, 512, 1, " blocks"));
    private final BoolSetting onlyMobs = add(new BoolSetting("onlyMobs", "Only mobs", false));
    private final BoolSetting onlyItems = add(new BoolSetting("onlyItems", "Only items", false));

    public EntityCount() {
        super("entities", "Entity Count", "How many entities are loaded around you.");
        setPosition(150, 60);
    }

    private String format(int count) {
        String prefix = onlyMobs.get() ? "Mobs count" : onlyItems.get() ? "Items count" : "Entity count";
        int r = range.get(), y = yRange.get();
        String radius = r == y ? String.format(Locale.ROOT, "radius %d", r) : String.format(Locale.ROOT, "r%d y%d", r, y);
        return prefix + ": " + count + " (" + radius + ")";
    }

    @Override
    protected String text(HudContext ctx) {
        var level = ctx.level();
        var player = ctx.player();
        if (level == null || player == null) return null;
        int count = 0;
        for (Entity e : level.entitiesForRendering()) {
            if (e == player) continue;
            if (onlyMobs.get() && !(e instanceof Mob)) continue;
            if (onlyItems.get() && !(e instanceof ItemEntity)) continue;
            if (e.position().closerThan(player.position(), range.get(), yRange.get())) count++;
        }
        return format(count);
    }

    @Override
    protected String sample() {
        return format(10);
    }
}
