package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import net.minecraft.world.level.LightLayer;

public class LightLevel extends TextHud {
    private final BoolSetting showSky = add(new BoolSetting("showSky", "Show sky light", false));

    public LightLevel() {
        super("light", "Light Level", "Light", "Block light at your feet (mobs spawn at 0).");
        setPosition(5, 104);
    }

    @Override
    protected String value(HudContext ctx) {
        var p = ctx.player();
        var level = ctx.level();
        if (p == null || level == null) return null;
        var engine = level.getLightEngine();
        int block = engine.getLayerListener(LightLayer.BLOCK).getLightValue(p.blockPosition());
        if (!showSky.get()) return Integer.toString(block);
        int sky = engine.getLayerListener(LightLayer.SKY).getLightValue(p.blockPosition());
        return block + " (sky " + sky + ")";
    }

    @Override
    protected String sample() {
        return "15";
    }
}
