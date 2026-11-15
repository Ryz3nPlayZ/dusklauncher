package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.util.Fmt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;

/** "42 ms": the latency the server reports for you in the tab list. */
public class Ping extends TextHud {
    private static final int GREEN = 0xFF55FF55, RED = 0xFFFF5555;
    private final BoolSetting dynamicColor = add(new BoolSetting("dynamicColor", "Colour by latency", true));
    private final BoolSetting hideWhenOffline = add(new BoolSetting("hideWhenOffline", "Hide in singleplayer", true));

    public Ping() {
        super("ping", "Ping", "Your latency to the server.");
        setPosition(5, 27);
        setEnabled(true);
    }

    public static int ping(Minecraft mc) {
        if (mc.thePlayer == null || mc.getNetHandler() == null) return 0;
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        return info == null ? 0 : Math.max(0, info.getResponseTime());
    }

    @Override
    protected String text(HudContext ctx) {
        if (ctx.player() == null) return null;
        if (ctx.mc().isSingleplayer()) return hideWhenOffline.get() ? null : "Offline";
        return ping(ctx.mc()) + " ms";
    }

    @Override
    protected String sample() { return "20 ms"; }

    @Override
    protected int color(HudContext ctx) {
        if (!dynamicColor.get()) return textColor();
        int ping = ctx.editing() && ctx.mc().isSingleplayer() ? 20 : ping(ctx.mc());
        return Fmt.lerpColor(GREEN, RED, ping / 1000f);
    }
}
