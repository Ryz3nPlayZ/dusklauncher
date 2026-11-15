package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.util.Fmt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

public class Speed extends TextHud {
    private final IntSetting digits = add(new IntSetting("digits", "Decimals", 1, 0, 4));
    private final ChoiceSetting unit = add(new ChoiceSetting("unit", "Unit", "m/s", "m/s", "km/h", "mph", "knots"));
    private final BoolSetting ignoreY = add(new BoolSetting("ignoreYSpeed", "Ignore vertical speed", false));

    private double perTick;

    public Speed() {
        super("speed", "Speedometer", "How fast you are moving.");
        setPosition(5, 82);
    }

    @Override
    public void tick() {
        EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
        if (p == null) {
            perTick = 0;
            return;
        }
        double dx = p.posX - p.prevPosX, dy = p.posY - p.prevPosY, dz = p.posZ - p.prevPosZ;
        perTick = Math.sqrt(dx * dx + dz * dz + (ignoreY.get() ? 0 : dy * dy));
    }

    private String format(double blocksPerTick) {
        double mps = blocksPerTick * 20;
        double v = mps;
        if (unit.is("km/h")) v = mps * 3.6;
        else if (unit.is("mph")) v = mps * 2.2369362921;
        else if (unit.is("knots")) v = mps * 1.9438452492;
        return Fmt.fixed(v, digits.get()) + " " + unit.get();
    }

    @Override
    protected String text(HudContext ctx) { return ctx.player() == null ? null : format(perTick); }

    @Override
    protected String sample() { return format(0); }
}
