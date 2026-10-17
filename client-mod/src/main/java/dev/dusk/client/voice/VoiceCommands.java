package dev.dusk.client.voice;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.dusk.client.social.Social;
import dev.dusk.client.social.SocialNotifier;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.dusk.client.compat.ClientCmd.argument;
import static dev.dusk.client.compat.ClientCmd.literal;

/** /duskvoice (or /dvoice): calls, mute and deafen, and per-player volume. */
public final class VoiceCommands {
    private static final int OK = 0x55FF7F, BAD = 0xFF5555, INFO = 0xAAAAAA;

    private static final SuggestionProvider<FabricClientCommandSource> FRIENDS = (ctx, b) ->
            SharedSuggestionProvider.suggest(SocialNotifier.knownFriends().stream().map(Social.Friend::username).toList(), b);

    private static final SuggestionProvider<FabricClientCommandSource> PEOPLE = (ctx, b) -> {
        List<String> names = new ArrayList<>();
        var level = Minecraft.getInstance().level;
        if (level != null) for (Player p : level.players()) names.add(p.getScoreboardName());
        names.addAll(DuskVoice.members().values());
        return SharedSuggestionProvider.suggest(names.stream().distinct().toList(), b);
    };

    private VoiceCommands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
        var root = dispatcher.register(build("duskvoice"));
        dispatcher.register(literal("dvoice").redirect(root));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> build(String name) {
        return literal(name)
                .executes(c -> say(c, Voice.status(), INFO))
                .then(literal("call").then(argument("friend", StringArgumentType.word()).suggests(FRIENDS).executes(c -> {
                    if (!on(c)) return 0;
                    Social.Friend f = friend(StringArgumentType.getString(c, "friend"));
                    if (f == null) return say(c, "That isn't one of your Dusk friends.", BAD);
                    if (!DuskVoice.connected()) return say(c, "Dusk voice isn't connected yet.", BAD);
                    DuskVoice.call(DuskVoice.parse(f.uuid()));
                    return say(c, "Calling " + f.username() + "...", OK);
                })))
                .then(literal("accept").executes(c -> {
                    if (DuskVoice.ringing() == null) return say(c, "Nobody is calling you.", BAD);
                    DuskVoice.accept();
                    return say(c, "Joined the call.", OK);
                }))
                .then(literal("decline").executes(c -> {
                    if (DuskVoice.ringing() == null) return say(c, "Nobody is calling you.", BAD);
                    DuskVoice.decline();
                    return say(c, "Declined.", INFO);
                }))
                .then(literal("hangup").executes(c -> {
                    if (!DuskVoice.inCall()) return say(c, "You aren't in a call.", BAD);
                    DuskVoice.hangUp();
                    return say(c, "Left the call.", INFO);
                }))
                .then(literal("mute").executes(c -> {
                    VoiceChat m = VoiceChat.instance();
                    if (m == null) return 0;
                    m.setMuted(!m.muted());
                    return say(c, m.muted() ? "Microphone muted." : "Microphone on.", m.muted() ? BAD : OK);
                }))
                .then(literal("deafen").executes(c -> {
                    VoiceChat m = VoiceChat.instance();
                    if (m == null) return 0;
                    m.setDeafened(!m.deafened());
                    return say(c, m.deafened() ? "Deafened." : "Undeafened.", m.deafened() ? BAD : OK);
                }))
                .then(literal("volume").then(argument("player", StringArgumentType.word()).suggests(PEOPLE)
                        .executes(c -> {
                            UUID who = person(StringArgumentType.getString(c, "player"));
                            if (who == null) return say(c, "No one by that name is here.", BAD);
                            return say(c, StringArgumentType.getString(c, "player") + " is at " + Voice.volume(who) + "%.", INFO);
                        })
                        .then(argument("percent", IntegerArgumentType.integer(0, 200)).executes(c -> {
                            String n = StringArgumentType.getString(c, "player");
                            UUID who = person(n);
                            if (who == null) return say(c, "No one by that name is here.", BAD);
                            int pct = IntegerArgumentType.getInteger(c, "percent");
                            Voice.setVolume(who, pct);
                            return say(c, pct == 0 ? n + " is muted for you." : n + " set to " + pct + "%.", OK);
                        }))));
    }

    private static boolean on(CommandContext<FabricClientCommandSource> c) {
        VoiceChat m = VoiceChat.instance();
        if (m != null && m.enabled()) return true;
        say(c, "Turn on Voice Chat in the Dusk menu first.", BAD);
        return false;
    }

    @Nullable
    private static Social.Friend friend(String name) {
        for (Social.Friend f : SocialNotifier.knownFriends()) if (f.username().equalsIgnoreCase(name)) return f;
        return null;
    }

    @Nullable
    private static UUID person(String name) {
        var level = Minecraft.getInstance().level;
        if (level != null) for (Player p : level.players()) if (p.getScoreboardName().equalsIgnoreCase(name)) return p.getUUID();
        for (Map.Entry<UUID, String> e : DuskVoice.members().entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        for (Map.Entry<UUID, SvcClient.Peer> e : SvcClient.peers.entrySet()) if (e.getValue().name().equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    private static int say(CommandContext<FabricClientCommandSource> c, String text, int rgb) {
        c.getSource().sendFeedback(Component.literal(text).withColor(rgb));
        return 1;
    }
}
