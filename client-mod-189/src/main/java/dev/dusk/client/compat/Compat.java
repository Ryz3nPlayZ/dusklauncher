package dev.dusk.client.compat;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.util.Map;

/** The version-specific calls the menu code shared with 1.21.11 makes, on 1.8.9. */
public final class Compat {
    private Compat() {}

    public static void setScreen(Minecraft mc, GuiScreen screen) {
        mc.displayGuiScreen(screen);
    }

    public static GuiScreen currentScreen(Minecraft mc) {
        return mc.currentScreen;
    }

    public static boolean hudHidden(Minecraft mc) {
        return mc.gameSettings.hideGUI;
    }

    public static GuiScreen optionsScreen(GuiScreen parent, Minecraft mc) {
        return new GuiOptions(parent, mc.gameSettings);
    }

    public static String keyCategoryLabel(KeyBinding key) {
        return I18n.format(key.getKeyCategory());
    }

    /** Window pixels per GUI pixel (the GUI scale). */
    public static int guiScale(Minecraft mc) {
        return new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor();
    }

    public static String modVersion() {
        ModContainer mod = Loader.instance().getIndexedModList().get("duskclient");
        return mod != null ? mod.getVersion() : "";
    }

    private static String skinFor;
    private static String skin;
    private static boolean slim;
    /** Wall-clock time a fetch for {@link #skinFor} may start again; 0 = any time. */
    private static volatile long retryAt;
    private static volatile boolean fetching;

    /**
     * The signed-in player's skin as a texture id, or the default skin until
     * it has loaded. The session profile carries no textures on 1.8.9, so
     * vanilla's skin cache only ever answers with Steve: the profile is
     * filled from the session server off the main thread once, and the skin
     * it names is then downloaded through the skin manager like any other.
     */
    public static String localSkin(Minecraft mc) {
        final GameProfile profile = mc.getSession().getProfile();
        String key = profile.getName();
        if (!key.equals(skinFor)) {
            skinFor = key;
            skin = null;
            retryAt = 0;
        }
        if (skin != null && !skin.startsWith("minecraft:textures/entity/")) return skin;
        if (!fetching && profile.getId() != null && System.currentTimeMillis() >= retryAt) {
            fetching = true;
            final Minecraft client = mc;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    fetchSkin(client, profile);
                }
            }, "duskclient-skin");
            t.setDaemon(true);
            t.start();
        }
        if (skin == null) {
            skin = profile.getId() != null
                    ? DefaultPlayerSkin.getDefaultSkin(profile.getId()).toString()
                    : DefaultPlayerSkin.getDefaultSkinLegacy().toString();
            slim = profile.getId() != null && "slim".equals(DefaultPlayerSkin.getSkinType(profile.getId()));
        }
        return skin;
    }

    /** Off the main thread: profile textures from the session server, then the download on the main thread. */
    private static void fetchSkin(final Minecraft mc, final GameProfile session) {
        final String key = session.getName();
        try {
            GameProfile filled = session.getProperties().containsKey("textures")
                    ? session
                    : mc.getSessionService().fillProfileProperties(new GameProfile(session.getId(), key), false);
            Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures = mc.getSessionService().getTextures(filled, false);
            final MinecraftProfileTexture t = textures.get(MinecraftProfileTexture.Type.SKIN);
            if (t == null) {
                // no custom skin on the account: the default is right, ask again later
                retryAt = System.currentTimeMillis() + 10 * 60_000L;
                fetching = false;
                return;
            }
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    try {
                        ResourceLocation loc = mc.getSkinManager().loadSkin(t, MinecraftProfileTexture.Type.SKIN);
                        if (loc != null && key.equals(skinFor)) {
                            skin = loc.toString();
                            slim = "slim".equals(t.getMetadata("model"));
                        }
                    } catch (Throwable ignored) {
                        retryAt = System.currentTimeMillis() + 60_000L;
                    }
                    fetching = false;
                }
            });
        } catch (Throwable e) {
            // offline or rate limited: the default skin for now, try again in a minute
            retryAt = System.currentTimeMillis() + 60_000L;
            fetching = false;
        }
    }

    /** Whether {@link #localSkin}'s skin has the thin (Alex) arms. */
    public static boolean localSkinSlim(Minecraft mc) {
        localSkin(mc);
        return slim;
    }
}
