package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import net.minecraft.client.multiplayer.PlayerInfo;

public class Ping extends TextHud {
    public Ping() {
        super("ping", "Ping", "Ping", "Your latency to the server, as the tab list reports it.");
        setPosition(5, 27);
        setEnabled(true);
    }

    @Override
    protected String value(HudContext ctx) {
        var p = ctx.player();
        var conn = ctx.mc().getConnection();
        if (p == null || conn == null) return null;
        PlayerInfo info = conn.getPlayerInfo(p.getUUID());
        return info == null ? null : info.getLatency() + " ms";
    }

    @Override
    protected String sample() {
        return "0 ms";
    }
}
