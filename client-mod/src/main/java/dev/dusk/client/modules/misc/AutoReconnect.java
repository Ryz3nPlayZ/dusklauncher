package dev.dusk.client.modules.misc;

import dev.dusk.client.compat.ScreenWidgets;
import dev.dusk.client.gui.DuskTitleScreen;
import dev.dusk.client.media.MediaBackend;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The reconnect button utility clients put on the disconnect screen (the idea
 * of Meteor's AutoReconnect and Wurst's Reconnect; both GPL, so written from
 * how they behave, not their code): after losing a server, a Reconnect button
 * sits under vanilla's, and with Automatically on it counts down and rejoins
 * by itself. Leaving for the server list or title screen forgets the server.
 */
public class AutoReconnect extends Module {
    private static AutoReconnect instance;

    private final BoolSetting automatic = add(new BoolSetting("automatic", "Automatically", false));
    private final IntSetting delay = add(new IntSetting("delay", "Delay", 5, 1, 60, 1, "s"));

    /** The server you were last playing on, kept from joining it. */
    @Nullable private ServerData server;
    /** Set when that server dropped you; the disconnect screen offers it back. */
    private boolean lost;

    public AutoReconnect() {
        super("autoreconnect", "Auto Reconnect", Category.MISC,
                "Adds a Reconnect button to the disconnect screen, and can rejoin by itself after a countdown.");
        instance = this;
        setEnabled(true);
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            AutoReconnect m = instance;
            if (m == null) return;
            ServerData data = client.getCurrentServer();
            boolean remote = data != null && !data.isRealm() && !client.isLocalServer() && !MediaBackend.replaying();
            m.server = remote ? data : null;
            m.lost = false;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (instance != null && instance.server != null) instance.lost = true;
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            AutoReconnect m = instance;
            if (m == null) return;
            if (screen instanceof DisconnectedScreen) m.decorate(client, screen);
            else if (screen instanceof JoinMultiplayerScreen || screen instanceof DuskTitleScreen) m.lost = false;
        });
    }

    private void decorate(Minecraft mc, Screen screen) {
        if (!enabled() || !lost || server == null) return;
        var buttons = ScreenWidgets.of(screen);
        int x = screen.width / 2 - 100, width = 200, bottom = screen.height / 2;
        for (AbstractWidget b : buttons) {
            if (b.getY() + b.getHeight() >= bottom) {
                bottom = b.getY() + b.getHeight();
                x = b.getX();
                width = b.getWidth();
            }
        }
        int y = Math.min(bottom + 4, screen.height - 24), w = width;
        boolean counting = automatic.get();
        int half = (width - 4) / 2;
        Button reconnect = Button.builder(Component.literal("Reconnect"), b -> reconnect(mc))
                .bounds(x, y, counting ? half : width, 20).build();
        buttons.add(reconnect);
        if (!counting) return;
        boolean[] stopped = {false};
        Button cancel = Button.builder(Component.literal("Cancel"), b -> {
            stopped[0] = true;
            b.visible = false;
            reconnect.setWidth(w);
            reconnect.setMessage(Component.literal("Reconnect"));
        }).bounds(x + width - half, y, half, 20).build();
        buttons.add(cancel);

        int[] ticks = {delay.get() * 20};
        reconnect.setMessage(label(ticks[0]));
        ScreenEvents.afterTick(screen).register(s -> {
            if (stopped[0]) return;
            if (--ticks[0] <= 0) {
                stopped[0] = true;
                reconnect(mc);
            }
            else if (ticks[0] % 20 == 19) reconnect.setMessage(label(ticks[0]));
        });
    }

    private static Component label(int ticks) {
        return Component.literal("Reconnect (" + (ticks + 19) / 20 + ")");
    }

    private void reconnect(Minecraft mc) {
        ServerData data = server;
        if (data == null) return;
        // a failed attempt lands on a new disconnect screen, which counts down again
        Screen parent = new JoinMultiplayerScreen(new DuskTitleScreen());
        ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(data.ip), data, false, null);
    }
}
