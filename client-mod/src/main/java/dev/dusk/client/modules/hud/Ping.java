package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.PingTracker;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import net.minecraft.client.Minecraft;

/** Flex-HUD's Ping: "42 ms", measured with its own ping requests and green-to-red by latency. */
public class Ping extends TextHud {
    private static final int GREEN = 0xFF55FF55, RED = 0xFFFF5555;

    private final BoolSetting dynamicColor = add(new BoolSetting("dynamicColor", "Colour by latency", true));
    private final BoolSetting hideWhenOffline = add(new BoolSetting("hideWhenOffline", "Hide in singleplayer", true));

    public Ping() {
        super("ping", "Ping", "Your latency to the server.");
        setPosition(5, 27);
        setEnabled(true);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null) PingTracker.tick(mc);
    }

    @Override
    protected String text(HudContext ctx) {
        if (ctx.player() == null) return null;
        if (ctx.mc().getCurrentServer() == null) return hideWhenOffline.get() ? null : "Offline";
        return PingTracker.ping() + " ms";
    }

    @Override
    protected String sample() {
        return "20 ms";
    }

    @Override
    protected int color(HudContext ctx) {
        if (!dynamicColor.get()) return textColor();
        long ping = ctx.editing() && ctx.mc().getCurrentServer() == null ? 20 : PingTracker.ping();
        return Tps.lerp(ping / 1000f, GREEN, RED);
    }
}
