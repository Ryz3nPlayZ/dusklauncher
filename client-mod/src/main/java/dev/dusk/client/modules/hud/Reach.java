package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.ClickTracker;
import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import net.minecraft.world.phys.EntityHitResult;

/** Distance of the last entity you hit. */
public class Reach extends TextHud {
    private int lastSerial = -1;
    private double lastReach = -1;

    public Reach() {
        super("reach", "Reach", "Reach", "Distance to the last entity you attacked.");
        setPosition(150, 93);
    }

    @Override
    protected String value(HudContext ctx) {
        var mc = ctx.mc();
        if (mc.player == null) return null;
        int serial = ClickTracker.attackSerial();
        if (serial != lastSerial) {
            lastSerial = serial;
            if (mc.hitResult instanceof EntityHitResult hit) {
                lastReach = hit.getLocation().distanceTo(mc.player.getEyePosition());
            }
        }
        return lastReach < 0 ? null : Fmt.fixed(lastReach, 2) + " blocks";
    }

    @Override
    protected String sample() {
        return "3.00 blocks";
    }
}
