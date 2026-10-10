package dev.dusk.client.modules.hud;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Icons;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.ItemCounts;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.KeySetting;
import dev.dusk.client.social.SocialNotifier;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.dusk.client.compat.ClientCmd.argument;
import static dev.dusk.client.compat.ClientCmd.literal;

/**
 * uku3lig's Totem Counter: counts every player's totem pops and puts the
 * count after their name ("Name | -3"), and shows how many totems you have
 * left above the hotbar, red when you're nearly out. The pop counts reset
 * with F10, /resetcounter, a dead player's tab entry or a round-end message.
 */
public class TotemCounter extends TextHud {
    /** The entity event the server sends when a totem saves someone. */
    private static final byte TOTEM_EVENT = 35;
    private static final List<String> ROUND_END = List.of(
            "Winners:", "has won the round.", "has won the game!", "Winner: NONE!", "Match Complete");
    private static final ItemStack TOTEM = new ItemStack(Items.TOTEM_OF_UNDYING);
    private static final int ICON = 16;

    private static final Map<UUID, Integer> POPS = new HashMap<>();
    private static TotemCounter instance;

    private final BoolSetting display = add(new BoolSetting("display", "Show your totem count", true));
    private final BoolSetting aboveHotbar = add(new BoolSetting("aboveHotbar", "Keep it above the hotbar (dragging it turns this off)", true));
    private final BoolSetting showOwnPops = add(new BoolSetting("showPopCounter", "Count your pops instead", false));
    private final BoolSetting displayColors = add(new BoolSetting("displayColors", "Colour the count by how many are left", true));
    private final BoolSetting coloredXpBar = Compat.MODERN_CLIENT_HOOKS
            ? add(new BoolSetting("coloredXpBar", "Colour the XP bar by totems left", false)) : null;
    private final BoolSetting alwaysShowBar = Compat.MODERN_CLIENT_HOOKS
            ? add(new BoolSetting("alwaysShowBar", "Colour it with more than 10 too", false)) : null;

    private final BoolSetting counterEnabled = add(new BoolSetting("counterEnabled", "Pops after player names", true), "Pop counter");
    private final BoolSetting separator = add(new BoolSetting("separator", "A | before the count", true), "Pop counter");
    private final BoolSetting counterColors = add(new BoolSetting("counterColors", "Colour the count", true), "Pop counter");
    private final BoolSetting showInTab = add(new BoolSetting("showInTab", "In the tab list too", false), "Pop counter");
    private final KeySetting resetKey = add(new KeySetting("totems_reset", "Reset pop counter", InputConstants.KEY_F10), "Keybinds");

    /** Where {@link #aboveHotbar} last put the box, to tell a drag in the editor from our own move. */
    private int placedX = Integer.MIN_VALUE, placedY;

    public TotemCounter() {
        super("totems", "Totem Counter", "Totems you have left, and every player's totem pops after their name.");
        instance = this;
        setPosition(200, 200);
    }

    /* ── pops ── */

    /** From ClientPacketListener.handleEntityEvent, on the client thread. */
    public static void onEntityEvent(ClientboundEntityEventPacket packet) {
        if (packet.getEventId() != TOTEM_EVENT || instance == null || !instance.enabled()) return;
        ClientLevel level = Minecraft.getInstance().level;
        Entity entity = level == null ? null : packet.getEntity(level);
        if (entity instanceof Player player) POPS.merge(player.getUUID(), 1, Integer::sum);
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> checkRoundEnd(message));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, at) -> checkRoundEnd(message));
    }

    private static void checkRoundEnd(Component message) {
        if (POPS.isEmpty()) return;
        String text = message.getString();
        for (String end : ROUND_END) {
            if (text.contains(end)) {
                POPS.clear();
                return;
            }
        }
    }

    public void tickKeys() {
        while (resetKey.mapping().consumeClick()) {
            if (!enabled()) continue;
            POPS.clear();
            SocialNotifier.toast(Icons.RESET, "Pop counter reset", "You can start counting again");
        }
    }

    /** Player.getDisplayName: the name with " | -N" after it once they've popped. */
    public static Component withPops(Player player, Component name) {
        TotemCounter m = instance;
        if (m == null || !m.enabled() || !m.counterEnabled.get()) return name;
        Integer pops = POPS.get(player.getUUID());
        return pops == null ? name : m.appendPops(name, pops);
    }

    private Component appendPops(Component name, int pops) {
        MutableComponent label = name.copy().append(" ");
        if (separator.get()) label.append(Component.literal("| ").withStyle(ChatFormatting.GRAY));
        MutableComponent count = Component.literal("-" + pops);
        if (counterColors.get()) count.setStyle(Style.EMPTY.withColor(popColor(pops)));
        return label.append(count);
    }

    /** PlayerTabOverlay.getNameForDisplay; a dead player's count is dropped here. */
    public static Component tabName(PlayerInfo info, Component name) {
        TotemCounter m = instance;
        if (m == null || !m.enabled() || !m.showInTab.get() || POPS.isEmpty()) return name;
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.level == null) return name;
        for (UUID id : POPS.keySet()) {
            if (connection.getPlayerInfo(id) != info) continue;
            Player player = mc.level.getPlayerByUUID(id);
            if (player == null) return name;
            if (!player.isAlive()) {
                POPS.remove(id);
                return name;
            }
            return withPops(player, name);
        }
        return name;
    }

    /**
     * A text display riding a player (servers draw nametags that way): the
     * line with their name gets the count, or null to leave the lines alone.
     */
    public static Component textDisplayLine(Player player, FormattedCharSequence line) {
        TotemCounter m = instance;
        if (m == null || !m.enabled() || !m.counterEnabled.get()) return null;
        if (!player.isAlive()) {
            POPS.remove(player.getUUID());
            return null;
        }
        Integer pops = POPS.get(player.getUUID());
        if (pops == null) return null;
        Component text = styled(line);
        String plain = text.getString();
        if (plain.isBlank() || !plain.contains(player.getScoreboardName())) return null;
        return m.appendPops(text, pops);
    }

    /** A laid-out line back to a styled component, run by run. */
    private static Component styled(FormattedCharSequence line) {
        MutableComponent out = Component.empty();
        StringBuilder run = new StringBuilder();
        Style[] runStyle = {null};
        line.accept((index, style, codePoint) -> {
            if (runStyle[0] != null && !style.equals(runStyle[0])) {
                out.append(Component.literal(run.toString()).setStyle(runStyle[0]));
                run.setLength(0);
            }
            runStyle[0] = style;
            run.appendCodePoint(codePoint);
            return true;
        });
        if (!run.isEmpty()) out.append(Component.literal(run.toString()).setStyle(runStyle[0]));
        return out;
    }

    public static int popColor(int pops) {
        return switch (pops) {
            case 1, 2 -> 0xFF55FF55;
            case 3, 4 -> 0xFF00AA00;
            case 5, 6 -> 0xFFFFFF55;
            case 7, 8 -> 0xFFFFAA00;
            default -> 0xFFFF5555;
        };
    }

    public static int totemColor(int totems) {
        return switch (totems) {
            case 1, 2 -> 0xFFFF5555;
            case 3, 4 -> 0xFFFFAA00;
            case 5, 6 -> 0xFFFFFF55;
            case 7, 8 -> 0xFF00AA00;
            default -> 0xFF55FF55;
        };
    }

    /* ── commands: /resetcounter [player], /showpops [player] ── */

    private static final SuggestionProvider<FabricClientCommandSource> PLAYERS = (ctx, b) -> {
        ClientLevel level = Minecraft.getInstance().level;
        return SharedSuggestionProvider.suggest(level == null ? List.of()
                : level.players().stream().map(Player::getScoreboardName).toList(), b);
    };

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
        dispatcher.register(literal("resetcounter")
                .executes(c -> {
                    POPS.clear();
                    feedback(c, Component.literal("Reset every player's pops.").withStyle(ChatFormatting.GREEN));
                    return 1;
                })
                .then(argument("player", StringArgumentType.word()).suggests(PLAYERS).executes(c -> {
                    Player player = player(c);
                    if (player == null) return 0;
                    POPS.remove(player.getUUID());
                    feedback(c, Component.literal("Reset " + player.getScoreboardName() + "'s pops.").withStyle(ChatFormatting.GREEN));
                    return 1;
                })));
        dispatcher.register(literal("showpops")
                .executes(c -> {
                    if (POPS.isEmpty()) {
                        feedback(c, Component.literal("Nobody has popped a totem yet."));
                        return 1;
                    }
                    c.getSource().sendFeedback(Component.literal(" ====== ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal("Totem").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                            .append(Component.literal("Counter").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                            .append(Component.literal(" ====== ").withStyle(ChatFormatting.GRAY)));
                    ClientLevel level = Minecraft.getInstance().level;
                    POPS.forEach((id, pops) -> {
                        Player player = level == null ? null : level.getPlayerByUUID(id);
                        String name = player != null ? player.getScoreboardName() : id.toString();
                        c.getSource().sendFeedback(Component.literal(name).withStyle(ChatFormatting.DARK_AQUA)
                                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                                .append(Component.literal("-" + pops).withStyle(Style.EMPTY.withColor(popColor(pops)))));
                    });
                    return 1;
                })
                .then(argument("player", StringArgumentType.word()).suggests(PLAYERS).executes(c -> {
                    Player player = player(c);
                    if (player == null) return 0;
                    int pops = POPS.getOrDefault(player.getUUID(), 0);
                    MutableComponent name = Component.literal(player.getScoreboardName()).withStyle(ChatFormatting.DARK_AQUA);
                    feedback(c, pops == 0 ? name.append(Component.literal(" hasn't popped a totem.").withStyle(ChatFormatting.WHITE))
                            : name.append(Component.literal(" has popped ").withStyle(ChatFormatting.WHITE))
                                    .append(Component.literal(String.valueOf(pops)).withStyle(Style.EMPTY.withColor(popColor(pops))))
                                    .append(Component.literal(pops == 1 ? " totem." : " totems.").withStyle(ChatFormatting.WHITE)));
                    return 1;
                })));
    }

    private static Player player(CommandContext<FabricClientCommandSource> c) {
        String name = StringArgumentType.getString(c, "player");
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            for (Player p : level.players()) {
                if (p.getScoreboardName().equalsIgnoreCase(name)) return p;
            }
        }
        feedback(c, Component.literal("No player called " + name + " nearby.").withStyle(ChatFormatting.RED));
        return null;
    }

    private static void feedback(CommandContext<FabricClientCommandSource> c, Component message) {
        c.getSource().sendFeedback(Component.empty()
                .append(Component.literal("Totem").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(Component.literal("Counter").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(Component.literal(" » ").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD))
                .append(message));
    }

    /* ── HUD and XP bar ── */

    /** Totems you carry, or your own pops with "Count your pops instead". */
    public static int count(Player player) {
        TotemCounter m = instance;
        if (player == null || m == null) return 0;
        if (m.showOwnPops.get()) return POPS.getOrDefault(player.getUUID(), 0);
        return ItemCounts.itemCount(Items.TOTEM_OF_UNDYING, player.getInventory());
    }

    private int countColor(int count) {
        if (chroma.get()) return textColor();
        if (!displayColors.get()) return textColor();
        return showOwnPops.get() ? popColor(count) : totemColor(count);
    }

    /** The XP bar's colour when it should show totems left (1.21.11+), else 0. */
    public static int xpBarColor() {
        TotemCounter m = instance;
        if (m == null || !m.enabled() || m.coloredXpBar == null || !m.coloredXpBar.get()) return 0;
        int count = count(Minecraft.getInstance().player);
        if (count == 0 || (count > 10 && !m.alwaysShowBar.get())) return 0;
        return m.showOwnPops.get() ? popColor(count) : m.displayColors.get() ? totemColor(count) : 0xFFFFFFFF;
    }

    @Override
    protected String text(HudContext ctx) {
        if (!display.get()) return null;
        int count = count(ctx.player());
        if (count == 0) return null;
        return showOwnPops.get() ? "-" + count : Integer.toString(count);
    }

    @Override
    protected String sample() {
        return showOwnPops.get() ? "-2" : "2";
    }

    /** Above the hotbar the number sits centred under the icon; anywhere else beside it. */
    private boolean centred() {
        return aboveHotbar.get();
    }

    @Override
    public int width(HudContext ctx) {
        String v = current(ctx);
        int textW = v == null ? 0 : ctx.textWidth(v);
        return centred() ? Math.max(ICON, textW) : ICON + 2 + textW;
    }

    @Override
    public int height(HudContext ctx) {
        return centred() ? ICON + 2 : ICON;
    }

    @Override
    public void beforeDraw(HudContext ctx) {
        if (!aboveHotbar.get()) return;
        if (ctx.editing() && placedX != Integer.MIN_VALUE && (x() != placedX || y() != placedY)) {
            aboveHotbar.set(false);
            return;
        }
        // vanilla-scale box bottom where the mod puts it; a bigger box grows upwards
        int y = ctx.height() - 38 - 9 - (screenHeight(ctx) - (ICON + 2));
        if (ctx.player() != null && ctx.player().experienceLevel > 0) y -= 6;
        placedX = (ctx.width() - screenWidth(ctx)) / 2;
        placedY = y;
        setPosition(placedX, placedY);
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        String v = current(ctx);
        if (v == null) return;
        int textW = ctx.textWidth(v);
        int color = ctx.editing() && text(ctx) == null ? countColor(2) : countColor(count(ctx.player()));
        if (centred()) {
            int w = width(ctx);
            c.item(TOTEM, (w - ICON) / 2, 0);
            c.text(v, (w - textW) / 2, ICON + 2 - 9, color, shadow.get());
        } else if (x() + screenWidth(ctx) / 2 > ctx.width() / 2) {
            c.text(v, 0, ICON / 2 - 4, color, shadow.get());
            c.item(TOTEM, textW + 2, 0);
        } else {
            c.item(TOTEM, 0, 0);
            c.text(v, ICON + 2, ICON / 2 - 4, color, shadow.get());
        }
    }
}
