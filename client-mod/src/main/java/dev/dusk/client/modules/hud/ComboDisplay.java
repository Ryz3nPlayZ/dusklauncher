package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.ClickTracker;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Counts consecutive hits you land without taking damage. Client-side
 * heuristic: an attack click with an entity under the crosshair is a hit,
 * taking damage or a timeout resets it.
 */
public class ComboDisplay extends TextHud {
    private final IntSetting timeout = add(new IntSetting("timeout", "Reset after", 3, 1, 10, 1, "s"));

    private int combo;
    private int lastSerial = -1;
    private long lastHitMs;
    private boolean wasHurt;

    public ComboDisplay() {
        super("combo", "Combo Counter", "Combo", "Consecutive hits landed without getting hit back.");
        setPosition(150, 115);
    }

    @Override
    public void tick() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) {
            combo = 0;
            return;
        }
        boolean hurt = mc.player.hurtTime > 0;
        if (hurt && !wasHurt) combo = 0;
        wasHurt = hurt;
        if (combo > 0 && System.currentTimeMillis() - lastHitMs > timeout.get() * 1000L) combo = 0;
    }

    @Override
    protected String value(HudContext ctx) {
        var mc = ctx.mc();
        if (mc.player == null) return null;
        int serial = ClickTracker.attackSerial();
        if (serial != lastSerial) {
            lastSerial = serial;
            if (mc.hitResult instanceof EntityHitResult) {
                combo++;
                lastHitMs = System.currentTimeMillis();
            }
        }
        return Integer.toString(combo);
    }

    @Override
    protected String sample() {
        return "0";
    }
}
