package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import net.minecraft.world.level.LightLayer;

/** Flex-HUD's Light Level: block light at your feet, coloured by how safe it is from spawns. */
public class LightLevel extends TextHud {
    private final BoolSetting colorByLevel = add(new BoolSetting("colorByLevel", "Colour by light level", true));
    private int level = 7;

    public LightLevel() {
        super("light", "Light Level", "Block light at your feet (mobs spawn at 0).");
        setPosition(5, 104);
    }

    @Override
    protected String text(HudContext ctx) {
        var p = ctx.player();
        var world = ctx.level();
        if (p == null || world == null) return null;
        level = world.getBrightness(LightLayer.BLOCK, p.blockPosition());
        return "Light level: " + level;
    }

    @Override
    protected String sample() {
        level = 7;
        return "Light level: 7";
    }

    @Override
    protected int color(HudContext ctx) {
        if (!colorByLevel.get()) return textColor();
        if (level <= 0) return 0xFFFF0000;
        if (level <= 7) return 0xFFFF7F00;
        if (level <= 11) return 0xFFFFFF00;
        return textColor();
    }
}
