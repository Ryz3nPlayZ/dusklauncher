package dev.dusk.client.hud;

import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Base for the counter HUDs: an icon and how many you carry for each of a few
 * items. The inventory is walked once per game tick, not every frame; the text
 * goes left of the icon on the right half of the screen, like Armor Status.
 */
public abstract class ItemTally extends TextHud {
    private static final int ITEM = 16, V_GAP = 1, H_GAP = 4;

    protected final BoolSetting hideEmpty;
    private final ChoiceSetting layout = add(new ChoiceSetting("displayMode", "Layout", "Vertical", "Vertical", "Horizontal"));

    private static final class Entry {
        final ItemStack icon;
        final String text;
        final int color, width;

        Entry(ItemStack icon, String text, int color, int width) {
            this.icon = icon;
            this.text = text;
            this.color = color;
            this.width = width;
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();
    private long countedAt = Long.MIN_VALUE;
    private Object countedFor;

    protected ItemTally(String id, String name, String description, boolean hideEmptyByDefault) {
        super(id, name, description);
        hideEmpty = add(new BoolSetting("hideEmpty", "Hide items you have none of", hideEmptyByDefault));
    }

    /** The items counted, in display order. Called once per tick, so it may be rebuilt from settings. */
    protected abstract List<Item> items();

    protected int countColor(Item item, int count) { return textColor(); }

    private boolean vertical() { return layout.is("Vertical"); }

    private List<Entry> entries(HudContext ctx) {
        if (ctx.player() == null || ctx.level() == null) {
            if (ctx.editing()) {
                if (countedFor != this) rebuild(ctx, null);
            } else {
                entries.clear();
            }
            return entries;
        }
        long now = ctx.level().getTotalWorldTime();
        Object key = ctx.editing() ? Boolean.TRUE : ctx.player();
        if (now != countedAt || key != countedFor) {
            countedAt = now;
            rebuild(ctx, ctx.player().inventory);
            countedFor = key;
        }
        return entries;
    }

    private void rebuild(HudContext ctx, InventoryPlayer inv) {
        entries.clear();
        for (Item item : items()) {
            int n = inv == null ? item.getItemStackLimit() : ItemCounts.itemCount(item, inv);
            if (n == 0 && hideEmpty.get() && !ctx.editing()) continue;
            String text = Integer.toString(n);
            entries.add(new Entry(new ItemStack(item), text, countColor(item, n), ITEM + 1 + ctx.textWidth(text)));
        }
        countedFor = this;
    }

    @Override
    protected String text(HudContext ctx) { return entries(ctx).isEmpty() ? null : ""; }

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
        boolean flip = vertical() && x() + screenWidth(ctx) / 2.0 > ctx.width() / 2.0;
        int total = width(ctx);
        int x = 0, y = 0;
        for (Entry e : es) {
            int ex = flip ? total - e.width : x;
            int textW = ctx.textWidth(e.text);
            c.item(e.icon, flip ? ex + textW + 1 : ex, y);
            c.text(e.text, flip ? ex : ex + ITEM + 1, y + 4, chroma.get() ? textColor() : e.color, shadow.get());
            if (vertical()) y += ITEM + V_GAP;
            else x += e.width + H_GAP;
        }
    }
}
