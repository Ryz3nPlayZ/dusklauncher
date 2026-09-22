package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import net.minecraft.util.Mth;

public class Direction extends TextHud {
    private static final String[] POINTS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
    private static final String[] AXES = {"+Z", "-X +Z", "-X", "-X -Z", "-Z", "+X -Z", "+X", "+X +Z"};

    private final BoolSetting showAxis = add(new BoolSetting("showAxis", "Show axis", true));

    public Direction() {
        super("direction", "Direction", "Facing", "Compass heading you are looking toward.");
        setPosition(5, 60);
    }

    @Override
    protected String value(HudContext ctx) {
        var p = ctx.player();
        if (p == null) return null;
        float yaw = Mth.wrapDegrees(p.getYRot());
        int idx = Math.floorMod(Math.round(yaw / 45f), 8);
        return showAxis.get() ? POINTS[idx] + " (" + AXES[idx] + ")" : POINTS[idx];
    }

    @Override
    protected String sample() {
        return "N (-Z)";
    }
}
