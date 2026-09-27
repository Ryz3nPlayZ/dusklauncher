package dev.dusk.client.modules.hud;

import dev.dusk.client.cosmetics.CapeTexture;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;

import java.util.Arrays;

/** Flex-HUD's Server Address: the server's icon and the address you typed to join it. */
public class ServerAddress extends TextHud {
    private static final int ICON = 14;
    private static final String UNKNOWN = "minecraft:textures/misc/unknown_server.png";

    private final BoolSetting showIcon = add(new BoolSetting("showServerIcon", "Show server icon", true));
    private final BoolSetting hideWhenOffline = add(new BoolSetting("hideWhenOffline", "Hide in singleplayer", true));

    private int faviconHash;
    private boolean faviconTried;
    private CapeTexture favicon;

    public ServerAddress() {
        super("server", "Server Address", "The server you are connected to.");
        setPosition(150, 82);
    }

    @Override
    protected String text(HudContext ctx) {
        var mc = ctx.mc();
        if (mc.player == null) return null;
        var server = mc.getCurrentServer();
        if (server == null) return hideWhenOffline.get() ? null : "Offline";
        return server.ip;
    }

    @Override
    protected String sample() {
        return "play.hypixel.net";
    }

    private boolean iconShown(HudContext ctx) {
        return showIcon.get() && (ctx.mc().getCurrentServer() != null || ctx.editing());
    }

    @Override
    public int width(HudContext ctx) {
        return super.width(ctx) + (iconShown(ctx) ? ICON + 2 : 0);
    }

    @Override
    public int height(HudContext ctx) {
        return iconShown(ctx) ? ICON : super.height(ctx);
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        String v = current(ctx);
        if (v == null) return;
        if (!iconShown(ctx)) {
            c.text(v, 0, 0, color(ctx), shadow.get());
            return;
        }
        c.blit(iconTexture(ctx), 0, 0, 0, 0, ICON, ICON, ICON, ICON);
        c.text(v, ICON + 2, (ICON - ctx.lineHeight()) / 2, color(ctx), shadow.get());
    }

    /** The server's favicon, uploaded once per distinct image; the vanilla "unknown server" icon otherwise. */
    private String iconTexture(HudContext ctx) {
        var server = ctx.mc().getCurrentServer();
        byte[] png = server == null ? null : server.getIconBytes();
        if (png == null) return UNKNOWN;
        int hash = Arrays.hashCode(png);
        if (!faviconTried || hash != faviconHash) {
            faviconTried = true;
            if (favicon != null) favicon.release();
            favicon = CapeTexture.prepareFrames("favicon/" + Integer.toHexString(hash), png, 1, 100);
            faviconHash = hash;
            if (favicon != null) favicon.register();
        }
        return favicon == null ? UNKNOWN : favicon.current().toString();
    }
}
