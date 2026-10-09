package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lunar's Item Tracker / the Pickup Notifier mods: what you just picked up,
 * as "+N Name" beside the item, newest on top. Picking up more of the same
 * item adds to its line and keeps it up. Fed by the server's pickup packet
 * ({@code ItemPickupMixin}), so it costs nothing until something is picked
 * up. Written for Dusk; no code from those mods.
 */
public class ItemPickups extends TextHud {
    private static final int ITEM = 16, V_GAP = 1, FADE_MS = 500;
    private static ItemPickups instance;

    private final IntSetting seconds = add(new IntSetting("seconds", "Show each pickup for", 5, 1, 20, 1, " s"));
    private final IntSetting maxLines = add(new IntSetting("lines", "Most lines", 5, 1, 12));

    private static final class Entry {
        final ItemStack icon;
        final String name;
        int count;
        long at;

        Entry(ItemStack icon, String name, int count, long at) {
            this.icon = icon;
            this.name = name;
            this.count = count;
            this.at = at;
        }

        String text() {
            return "+" + count + " " + name;
        }
    }

    /** Newest first. Only touched on the client thread: the packet hook and the HUD both run there. */
    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> sample = List.of(
            new Entry(new ItemStack(Items.OAK_LOG), "Oak Log", 12, 0),
            new Entry(new ItemStack(Items.DIAMOND), "Diamond", 3, 0));
    private ClientLevel level;
    /** {@link #shown} for the frame {@link #shownFor} was built for: a frame asks for it four times. */
    private HudContext shownFor;
    private List<Entry> shownList = List.of();

    public ItemPickups() {
        super("itempickups", "Item Pickups", "Lists the items you just picked up and how many.");
        instance = this;
        setPosition(380, 140);
    }

    @Nullable
    public static ItemPickups active() {
        ItemPickups m = instance;
        return m != null && m.enabled() ? m : null;
    }

    /** {@code amount} of {@code stack} went into your inventory. */
    public static void picked(ItemStack stack, int amount) {
        ItemPickups m = active();
        if (m == null || stack.isEmpty() || amount <= 0) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < m.entries.size(); i++) {
            Entry e = m.entries.get(i);
            if (ItemStack.isSameItemSameComponents(e.icon, stack)) {
                e.count += amount;
                e.at = now;
                m.entries.remove(i);
                m.entries.add(0, e);
                m.shownFor = null;
                return;
            }
        }
        m.entries.add(0, new Entry(stack.copyWithCount(1), stack.getHoverName().getString(), amount, now));
        while (m.entries.size() > m.maxLines.get()) m.entries.remove(m.entries.size() - 1);
        m.shownFor = null;
    }

    private List<Entry> shown(HudContext ctx) {
        if (ctx == shownFor) return shownList;
        shownFor = ctx;
        if (ctx.level() != level) {
            // a new world or server: the last one's pickups don't carry over
            entries.clear();
            level = ctx.level();
        }
        long expiry = System.currentTimeMillis() - seconds.get() * 1000L;
        entries.removeIf(e -> e.at < expiry);
        while (entries.size() > maxLines.get()) entries.remove(entries.size() - 1);
        return shownList = entries.isEmpty() && ctx.editing() ? sample : entries;
    }

    @Override
    protected String text(HudContext ctx) {
        return shown(ctx).isEmpty() ? null : "";
    }

    @Override
    public int width(HudContext ctx) {
        int w = 0;
        for (Entry e : shown(ctx)) w = Math.max(w, ITEM + 1 + ctx.textWidth(e.text()));
        return w;
    }

    @Override
    public int height(HudContext ctx) {
        int n = shown(ctx).size();
        return n == 0 ? 0 : n * (ITEM + V_GAP) - V_GAP;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        List<Entry> list = shown(ctx);
        boolean right = x() + screenWidth(ctx) / 2.0 > ctx.width() / 2.0;
        int total = width(ctx), color = textColor();
        long fadeFrom = System.currentTimeMillis() - (seconds.get() * 1000L - FADE_MS);
        int y = 0;
        for (Entry e : list) {
            String text = e.text();
            int textW = ctx.textWidth(text);
            int x = right ? total - (ITEM + 1 + textW) : 0;
            // the last half second fades the text out; older fonts draw alpha under 4 as opaque
            int alpha = e.at >= fadeFrom ? 255 : Math.max(4, (int) (255 * (1 - (fadeFrom - e.at) / (float) FADE_MS)));
            c.item(e.icon, right ? x + textW + 1 : x, y);
            c.text(text, right ? x : x + ITEM + 1, y + 4, alpha << 24 | color & 0xFFFFFF, shadow.get());
            y += ITEM + V_GAP;
        }
    }
}
