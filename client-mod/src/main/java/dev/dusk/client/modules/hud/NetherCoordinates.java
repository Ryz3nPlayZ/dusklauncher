package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import net.minecraft.world.level.Level;

/** Where you would land on the other side of a portal. */
public class NetherCoordinates extends TextHud {
    public NetherCoordinates() {
        super("nethercoords", "Nether Coordinates", "Portal", "Overworld <-> Nether converted coordinates.");
        setPosition(5, 49);
    }

    @Override
    protected String value(HudContext ctx) {
        var p = ctx.player();
        var level = ctx.level();
        if (p == null || level == null) return null;
        if (level.dimension() == Level.OVERWORLD) {
            return (int) Math.floor(p.getX() / 8) + ", " + (int) Math.floor(p.getY()) + ", " + (int) Math.floor(p.getZ() / 8) + " (Nether)";
        }
        if (level.dimension() == Level.NETHER) {
            return (int) Math.floor(p.getX() * 8) + ", " + (int) Math.floor(p.getY()) + ", " + (int) Math.floor(p.getZ() * 8) + " (Overworld)";
        }
        return null;
    }

    @Override
    protected String sample() {
        return "0, 64, 0 (Nether)";
    }
}
