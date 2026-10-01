package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.hud.TpsTracker;
import dev.dusk.client.module.setting.IntSetting;

/**
 * A lag notice in the spirit of Meteor's LagNotifier HUD (GPL, so written
 * from how it behaves, not its code): servers send the time once a second,
 * so when none has come for a while the server has stalled or the connection
 * has dropped, and this says for how long. Hidden the rest of the time, and
 * in singleplayer, where pausing stops the updates on purpose.
 */
public class ServerLag extends TextHud {
    private final IntSetting after = add(new IntSetting("after", "Show after", 2, 1, 10, 1, "s"));

    public ServerLag() {
        super("serverlag", "Server Lag", "Says when the server has stopped responding, and for how long.", 0xFFFF5555);
        setPosition(230, 60);
    }

    @Override
    protected String text(HudContext ctx) {
        if (ctx.mc().isLocalServer() || ctx.mc().getConnection() == null) return null;
        long ms = TpsTracker.millisSinceUpdate();
        if (ms < after.get() * 1000L) return null;
        return "Server not responding (" + Fmt.fixed(ms / 1000.0, 1) + "s)";
    }

    @Override
    protected String sample() {
        return "Server not responding (3.2s)";
    }
}
