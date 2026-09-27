package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import net.minecraft.world.level.Level;

/** Flex-HUD's Nether Coordinates: where you would land through a portal, "Nether: 12 -40". */
public class NetherCoordinates extends TextHud {
    private final BoolSetting onlyOverworld = add(new BoolSetting("onlyWhenInOverworld", "Only in the Overworld", false));

    public NetherCoordinates() {
        super("nethercoords", "Nether Coordinates", "Overworld <-> Nether converted coordinates.");
        setPosition(5, 49);
    }

    @Override
    protected String text(HudContext ctx) {
        var p = ctx.player();
        var level = ctx.level();
        if (p == null || level == null) return null;
        if (level.dimension() == Level.OVERWORLD) {
            return "Nether: " + (int) Math.floor(p.getX() / 8) + " " + (int) Math.floor(p.getZ() / 8);
        }
        if (level.dimension() == Level.NETHER && !onlyOverworld.get()) {
            return "Overworld: " + (int) Math.floor(p.getX() * 8) + " " + (int) Math.floor(p.getZ() * 8);
        }
        return null;
    }

    @Override
    protected String sample() {
        return "Nether: 1 -6";
    }
}
