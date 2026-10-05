package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Your item cooldowns with the seconds left: ender pearls, chorus fruit,
 * wind charges, a shield an axe knocked out, goat horns and anything a
 * server gives a cooldown. The hotbar only shows a grey sweep, and only for
 * items in the hotbar. Fed by {@code ItemCooldownsMixin} with the player's
 * own cooldown clock, so it can't drift from what the game enforces.
 */
public class Cooldowns extends TextHud {
    private static final int ITEM = 16, V_GAP = 1, H_GAP = 4;

    private final ChoiceSetting layout = add(new ChoiceSetting("displayMode", "Layout", "Vertical", "Vertical", "Horizontal"));
    private final BoolSetting tenths = add(new BoolSetting("tenths", "Tenths of a second", true));

    /** Cooldown group id → its start and end on the cooldown clock, in the order they started. */
    private static final Map<String, int[]> active = new LinkedHashMap<>();
    private static int clock;
    /** The cooldown tracker {@link #active} belongs to; a new player brings a new one. */
    private static Object owner;
    private static final Map<String, ItemStack> icons = new HashMap<>();

    private record Entry(ItemStack icon, String text, int width) {}

    public Cooldowns() {
        super("cooldowns", "Cooldowns", "Seconds left on ender pearls, wind charges, a disabled shield and other item cooldowns.");
        setPosition(80, 140);
    }

    // ---- fed by ItemCooldownsMixin, on the client thread, for the local player only ----

    public static void started(Object tracker, String group, int start, int end) {
        adopt(tracker);
        active.put(group, new int[]{start, end});
        clock = start;
    }

    public static void ended(Object tracker, String group) {
        if (tracker == owner) active.remove(group);
    }

    public static void ticked(Object tracker, int now) {
        adopt(tracker);
        clock = now;
    }

    /** A new player (respawn, world change) starts with no cooldowns. */
    private static void adopt(Object tracker) {
        if (tracker == owner) return;
        owner = tracker;
        active.clear();
    }

    // ---- drawing ----

    private boolean vertical() {
        return layout.is("Vertical");
    }

    private static ItemStack icon(String group) {
        return icons.computeIfAbsent(group, g -> {
            for (Item item : BuiltInRegistries.ITEM) {
                if (String.valueOf(BuiltInRegistries.ITEM.getKey(item)).equals(g)) return new ItemStack(item);
            }
            return new ItemStack(Items.CLOCK);
        });
    }

    private List<Entry> entries(HudContext ctx) {
        List<Entry> out = new ArrayList<>();
        if (ctx.editing() && (ctx.player() == null || active.isEmpty())) {
            out.add(entry(ctx, new ItemStack(Items.ENDER_PEARL), 0.8f));
            out.add(entry(ctx, new ItemStack(Items.SHIELD), 3.4f));
            return out;
        }
        if (ctx.player() == null || ctx.player().getCooldowns() != owner) return out;
        for (Map.Entry<String, int[]> e : active.entrySet()) {
            float left = (e.getValue()[1] - clock - ctx.partialTick()) / 20f;
            if (left <= 0) continue;
            out.add(entry(ctx, icon(e.getKey()), left));
        }
        return out;
    }

    private Entry entry(HudContext ctx, ItemStack icon, float seconds) {
        String text = tenths.get() ? String.format(Locale.ROOT, "%.1fs", seconds) : (int) Math.ceil(seconds) + "s";
        return new Entry(icon, text, ITEM + 1 + ctx.textWidth(text));
    }

    @Override
    protected String text(HudContext ctx) {
        return entries(ctx).isEmpty() ? null : "";
    }

    @Override
    public int width(HudContext ctx) {
        List<Entry> es = entries(ctx);
        if (es.isEmpty()) return ctx.textWidth(name());
        int w = 0;
        for (Entry e : es) w = vertical() ? Math.max(w, e.width) : w + e.width + H_GAP;
        return vertical() ? w : w - H_GAP;
    }

    @Override
    public int height(HudContext ctx) {
        List<Entry> es = entries(ctx);
        if (es.isEmpty() || !vertical()) return ITEM;
        return es.size() * (ITEM + V_GAP) - V_GAP;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        List<Entry> es = entries(ctx);
        if (es.isEmpty()) {
            if (ctx.editing()) c.text(name(), 0, (ITEM - 8) / 2, textColor(), shadow.get());
            return;
        }
        boolean right = x() + screenWidth(ctx) / 2.0 > ctx.width() / 2.0;
        int total = width(ctx);
        int x = 0, y = 0;
        for (Entry e : es) {
            boolean flip = vertical() && right;
            int ex = flip ? total - e.width : x;
            int textW = ctx.textWidth(e.text);
            c.item(e.icon, flip ? ex + textW + 1 : ex, y);
            c.text(e.text, flip ? ex : ex + ITEM + 1, y + 4, textColor(), shadow.get());
            if (vertical()) y += ITEM + V_GAP;
            else x += e.width + H_GAP;
        }
    }
}
