package dev.dusk.client.modules.misc;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.TextSetting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Hypixel quality of life, from how AutoGG and the lobby-cleanup mods
 * behave: says gg once when a game ends, and drops the "joined the lobby!"
 * lines that flood lobby chat. Only acts while you are on Hypixel, and only
 * sends what Hypixel's rules allow (one chat line per game).
 */
public class HypixelTweaks extends Module {
    private static HypixelTweaks instance;

    /** End-of-game summary lines across Hypixel's minigames, matched on the plain text. */
    private static final Pattern GAME_END = Pattern.compile(
            "^\\s*(1st Killer - |1st Place - |Winner: |Winners: |Winning Team ?[-:] |Winner #1 |1st - |Top Survivors).*");
    private static final Pattern LOBBY_JOIN = Pattern.compile(".*(joined the lobby!|spooked into the lobby!|sled into the lobby!|slid into the lobby!).*");
    private static final int GG_DELAY_TICKS = 20, GG_COOLDOWN_MS = 10_000;

    private final BoolSetting autoGg = add(new BoolSetting("autoGg", "Auto GG", true), "Chat");
    private final TextSetting ggMessage = add(new TextSetting("ggMessage", "GG message", "gg", 32), "Chat");
    private final BoolSetting hideLobbyJoins = add(new BoolSetting("hideLobbyJoins", "Hide lobby join messages", true), "Chat");

    private int ggIn = -1;
    private long lastGg;

    public HypixelTweaks() {
        super("hypixel", "Hypixel Tweaks", Category.MISC,
                "On Hypixel: says gg when a game ends and hides the lobby join spam.");
        instance = this;
    }

    public static boolean onHypixel() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        if (server == null || server.ip == null) return false;
        String ip = server.ip.toLowerCase(Locale.ROOT);
        int colon = ip.lastIndexOf(':');
        if (colon > 0) ip = ip.substring(0, colon);
        return ip.equals("hypixel.net") || ip.endsWith(".hypixel.net");
    }

    public static void register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            HypixelTweaks m = instance;
            if (overlay || m == null || !m.enabled() || !onHypixel()) return true;
            String text = message.getString();
            if (m.hideLobbyJoins.get() && LOBBY_JOIN.matcher(text).matches()) return false;
            if (m.autoGg.get() && m.ggIn < 0 && System.currentTimeMillis() - m.lastGg > GG_COOLDOWN_MS
                    && GAME_END.matcher(text).matches()) {
                m.ggIn = GG_DELAY_TICKS;
            }
            return true;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            HypixelTweaks m = instance;
            if (m == null || m.ggIn < 0 || --m.ggIn > 0) return;
            m.ggIn = -1;
            String msg = m.ggMessage.get().trim();
            if (client.player == null || msg.isEmpty() || !m.enabled() || !onHypixel()) return;
            m.lastGg = System.currentTimeMillis();
            // /ac reaches everyone in the game even from party or guild chat
            client.player.connection.sendCommand("ac " + msg);
        });
    }
}
