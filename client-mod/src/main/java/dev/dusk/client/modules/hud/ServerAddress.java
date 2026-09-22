package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;

public class ServerAddress extends TextHud {
    public ServerAddress() {
        super("server", "Server Address", "Server", "The server you are connected to.");
        setPosition(150, 82);
    }

    @Override
    protected String value(HudContext ctx) {
        var mc = ctx.mc();
        if (mc.player == null) return null;
        var server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) return server.ip;
        return mc.hasSingleplayerServer() ? "Singleplayer" : null;
    }

    @Override
    protected String sample() {
        return "play.example.net";
    }
}
