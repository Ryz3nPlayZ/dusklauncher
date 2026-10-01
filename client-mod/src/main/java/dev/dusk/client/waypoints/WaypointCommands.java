package dev.dusk.client.waypoints;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.dusk.client.modules.misc.Waypoints;
import dev.dusk.client.waypoints.WaypointStore.Waypoint;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

import static dev.dusk.client.compat.ClientCmd.argument;
import static dev.dusk.client.compat.ClientCmd.literal;

/**
 * /waypoint (alias /wp), the chat side of the waypoint list: add (here or
 * at coordinates), remove, rename, move, show, hide, list, copy and import.
 * Names with spaces go in quotes. Client-side only; nothing reaches the server.
 */
public final class WaypointCommands {
    private WaypointCommands() {}

    private static final int GOLD = 0xFFC600, GREY = 0xAAAAAA, RED = 0xFF5555;

    private static final SuggestionProvider<FabricClientCommandSource> NAMES = (ctx, b) ->
            SharedSuggestionProvider.suggest(WaypointStore.current().stream().filter(w -> !w.death)
                    .map(w -> StringArgumentType.escapeIfRequired(w.name)), b);

    private static final SuggestionProvider<FabricClientCommandSource> WORLDS = (ctx, b) ->
            SharedSuggestionProvider.suggest(WaypointStore.otherWorlds().keySet().stream()
                    .map(StringArgumentType::escapeIfRequired), b);

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
        var root = literal("waypoint")
                .executes(WaypointCommands::list)
                .then(literal("list").executes(WaypointCommands::list))
                .then(literal("add")
                        .executes(c -> add(c, null, null))
                        .then(argument("name", StringArgumentType.string())
                                .executes(c -> add(c, StringArgumentType.getString(c, "name"), null))
                                .then(argument("x", IntegerArgumentType.integer())
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .executes(c -> add(c, StringArgumentType.getString(c, "name"), xyz(c))))))))
                .then(literal("remove").then(named().executes(WaypointCommands::remove)))
                .then(literal("rename").then(named()
                        .then(argument("newName", StringArgumentType.string()).executes(WaypointCommands::rename))))
                .then(literal("move").then(named()
                        .executes(c -> move(c, null))
                        .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                        .then(argument("z", IntegerArgumentType.integer())
                                                .executes(c -> move(c, xyz(c))))))))
                .then(literal("show").then(named().executes(c -> visible(c, true))))
                .then(literal("hide").then(named().executes(c -> visible(c, false))))
                .then(literal("copy").then(named().executes(WaypointCommands::copy)))
                .then(literal("import").then(argument("world", StringArgumentType.string()).suggests(WORLDS)
                        .executes(WaypointCommands::importWorld)));
        var node = dispatcher.register(root);
        dispatcher.register(literal("wp").redirect(node).executes(WaypointCommands::list));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource, String> named() {
        return argument("name", StringArgumentType.string()).suggests(NAMES);
    }

    private static int[] xyz(CommandContext<FabricClientCommandSource> c) {
        return new int[] {IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"),
                IntegerArgumentType.getInteger(c, "z")};
    }

    /* ── commands ── */

    private static int list(CommandContext<FabricClientCommandSource> c) {
        List<Waypoint> list = WaypointStore.current();
        if (list.isEmpty()) return fail(c, "No waypoints in this world. /waypoint add <name> makes one where you stand.");
        c.getSource().sendFeedback(Component.literal("Waypoints (" + list.size() + "):").withColor(GOLD));
        var player = Minecraft.getInstance().player;
        Waypoints m = Waypoints.active();
        for (Waypoint w : list) {
            String dist = "";
            if (player != null && m != null) {
                double[] pos = m.positionIn(w, Waypoints.dimension());
                if (pos != null) dist = "  " + Waypoints.formatDistance(Math.sqrt(player.distanceToSqr(pos[0] + 0.5, pos[1], pos[2] + 0.5)));
            }
            c.getSource().sendFeedback(Component.literal(" ■ ").withColor(w.color & 0xFFFFFF)
                    .append(Component.literal(w.name).withColor(w.visible ? 0xFFFFFF : GREY))
                    .append(Component.literal("  " + w.x + " " + w.y + " " + w.z + "  " + w.dim + dist + (w.visible ? "" : "  (hidden)")).withColor(GREY)));
        }
        return list.size();
    }

    private static int add(CommandContext<FabricClientCommandSource> c, String name, int[] at) {
        var player = Minecraft.getInstance().player;
        if (player == null || WaypointStore.worldKey() == null) return fail(c, "Join a world first.");
        if (name != null && WaypointStore.find(name) != null) return fail(c, "There is already a waypoint called \"" + name + "\".");
        Waypoint w = Waypoints.add(player, name);
        if (at != null) {
            w.x = at[0];
            w.y = at[1];
            w.z = at[2];
            WaypointStore.save();
        }
        return ok(c, "Added \"" + w.name + "\" at " + w.x + " " + w.y + " " + w.z + ".");
    }

    private static int remove(CommandContext<FabricClientCommandSource> c) {
        Waypoint w = named(c);
        if (w == null) return 0;
        WaypointStore.current().remove(w);
        WaypointStore.save();
        return ok(c, "Removed \"" + w.name + "\".");
    }

    private static int rename(CommandContext<FabricClientCommandSource> c) {
        Waypoint w = named(c);
        if (w == null) return 0;
        String next = StringArgumentType.getString(c, "newName").trim();
        if (next.isEmpty()) return fail(c, "The new name is empty.");
        Waypoint clash = WaypointStore.find(next);
        if (clash != null && clash != w) return fail(c, "There is already a waypoint called \"" + next + "\".");
        String old = w.name;
        w.name = next;
        WaypointStore.save();
        return ok(c, "Renamed \"" + old + "\" to \"" + next + "\".");
    }

    private static int move(CommandContext<FabricClientCommandSource> c, int[] at) {
        Waypoint w = named(c);
        if (w == null) return 0;
        var player = Minecraft.getInstance().player;
        if (at == null) {
            if (player == null) return fail(c, "Join a world first.");
            at = new int[] {Mth.floor(player.getX()), Mth.floor(player.getY()), Mth.floor(player.getZ())};
        }
        w.x = at[0];
        w.y = at[1];
        w.z = at[2];
        w.dim = Waypoints.dimension();
        WaypointStore.save();
        return ok(c, "Moved \"" + w.name + "\" to " + w.x + " " + w.y + " " + w.z + ".");
    }

    private static int visible(CommandContext<FabricClientCommandSource> c, boolean on) {
        Waypoint w = named(c);
        if (w == null) return 0;
        w.visible = on;
        WaypointStore.save();
        return ok(c, (on ? "Showing" : "Hiding") + " \"" + w.name + "\".");
    }

    private static int copy(CommandContext<FabricClientCommandSource> c) {
        Waypoint w = named(c);
        if (w == null) return 0;
        Minecraft.getInstance().keyboardHandler.setClipboard(w.x + " " + w.y + " " + w.z);
        return ok(c, "Copied \"" + w.name + "\"'s coordinates.");
    }

    private static int importWorld(CommandContext<FabricClientCommandSource> c) {
        String key = StringArgumentType.getString(c, "world");
        if (!WaypointStore.otherWorlds().containsKey(key)) return fail(c, "No other world called \"" + key + "\" has waypoints.");
        int n = WaypointStore.importFrom(key);
        return ok(c, "Imported " + n + " waypoint" + (n == 1 ? "" : "s") + " from " + WaypointStore.describe(key) + ".");
    }

    /* ── helpers ── */

    private static Waypoint named(CommandContext<FabricClientCommandSource> c) {
        String name = StringArgumentType.getString(c, "name");
        Waypoint w = WaypointStore.find(name);
        if (w == null) fail(c, "No waypoint called \"" + name + "\".");
        return w;
    }

    private static int ok(CommandContext<FabricClientCommandSource> c, String msg) {
        c.getSource().sendFeedback(Component.literal(msg).withColor(GOLD));
        return 1;
    }

    private static int fail(CommandContext<FabricClientCommandSource> c, String msg) {
        c.getSource().sendError(Component.literal(msg).withColor(RED));
        return 0;
    }
}
