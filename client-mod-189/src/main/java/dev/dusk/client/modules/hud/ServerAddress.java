package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.codec.binary.Base64;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/** Flex-HUD's Server Address: the server's icon and the address you typed to join it. */
public class ServerAddress extends TextHud {
    private static final int ICON = 14;
    private static final ResourceLocation UNKNOWN = new ResourceLocation("textures/misc/unknown_server.png");

    private final BoolSetting showIcon = add(new BoolSetting("showServerIcon", "Show server icon", true));
    private final BoolSetting hideWhenOffline = add(new BoolSetting("hideWhenOffline", "Hide in singleplayer", true));

    private String faviconFor;
    private ResourceLocation favicon;
    private DynamicTexture faviconTexture;

    public ServerAddress() {
        super("server", "Server Address", "The server you are connected to.");
        setPosition(150, 82);
    }

    @Override
    protected String text(HudContext ctx) {
        if (ctx.player() == null) return null;
        ServerData server = ctx.mc().getCurrentServerData();
        if (server == null) return hideWhenOffline.get() ? null : "Offline";
        return server.serverIP;
    }

    @Override
    protected String sample() { return "play.example.net"; }

    private boolean iconShown(HudContext ctx) {
        return showIcon.get() && (ctx.mc().getCurrentServerData() != null || ctx.editing());
    }

    @Override
    public int width(HudContext ctx) { return super.width(ctx) + (iconShown(ctx) ? ICON + 2 : 0); }

    @Override
    public int height(HudContext ctx) { return iconShown(ctx) ? ICON : super.height(ctx); }

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
    private ResourceLocation iconTexture(HudContext ctx) {
        ServerData server = ctx.mc().getCurrentServerData();
        String data = server == null ? null : server.getBase64EncodedIconData();
        if (data == null) return UNKNOWN;
        if (!data.equals(faviconFor)) {
            faviconFor = data;
            if (faviconTexture != null) faviconTexture.deleteGlTexture();
            faviconTexture = null;
            favicon = null;
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.decodeBase64(data)));
                if (img != null) {
                    faviconTexture = new DynamicTexture(img);
                    favicon = ctx.mc().getTextureManager().getDynamicTextureLocation("dusk_favicon", faviconTexture);
                }
            } catch (Exception ignored) {
                // a broken favicon just shows the unknown-server icon
            }
        }
        return favicon == null ? UNKNOWN : favicon;
    }
}
