package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Flex-HUD's Speedometer: how far you moved last tick, in m/s, km/h, mph or knots. */
public class Speed extends TextHud {
    private final IntSetting digits = add(new IntSetting("digits", "Decimals", 1, 0, 4));
    private final ChoiceSetting unit = add(new ChoiceSetting("unit", "Unit", "m/s", "m/s", "km/h", "mph", "knots"));
    private final BoolSetting ignoreY = add(new BoolSetting("ignoreYSpeed", "Ignore vertical speed", false));

    private Vec3 last;
    private double perTick;

    public Speed() {
        super("speed", "Speedometer", "How fast you are moving.");
        setPosition(5, 82);
    }

    @Override
    public void tick() {
        var p = Minecraft.getInstance().player;
        if (p == null) {
            last = null;
            perTick = 0;
            return;
        }
        Vec3 pos = p.position();
        if (last != null) {
            Vec3 d = pos.subtract(last);
            perTick = ignoreY.get() ? d.horizontalDistance() : d.length();
        }
        last = pos;
    }

    private String format(double blocksPerTick) {
        double mps = blocksPerTick * 20;
        double v = switch (unit.get()) {
            case "km/h" -> mps * 3.6;
            case "mph" -> mps * 2.2369362921;
            case "knots" -> mps * 1.9438452492;
            default -> mps;
        };
        return Fmt.fixed(v, digits.get()) + " " + unit.get();
    }

    @Override
    protected String text(HudContext ctx) {
        return ctx.player() == null ? null : format(perTick);
    }

    @Override
    protected String sample() {
        return format(0);
    }
}
