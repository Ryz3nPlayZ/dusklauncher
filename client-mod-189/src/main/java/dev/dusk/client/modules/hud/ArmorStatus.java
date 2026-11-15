package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.ItemCounts;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Each worn piece and the held item with its durability (in the durability
 * bar's colour) or, for stackables, how many you carry; plus your arrows while
 * a bow is in hand. On the right half of the screen the text goes before the item.
 */
public class ArmorStatus extends TextHud {
    private static final int ITEM = 16, V_GAP = 1, H_GAP = 4;
    private static final Item[] SAMPLE = {Items.diamond_helmet, Items.diamond_chestplate, Items.diamond_leggings,
            Items.diamond_boots, Items.bow};

    private final BoolSetting[] show = {
            add(new BoolSetting("showHelmet", "Show helmet", true)),
            add(new BoolSetting("showChestplate", "Show chestplate", true)),
            add(new BoolSetting("showLeggings", "Show leggings", true)),
            add(new BoolSetting("showBoots", "Show boots", true)),
            add(new BoolSetting("showHeldItem", "Show held item", true)),
    };
    private final BoolSetting showArrows = add(new BoolSetting("showArrows", "Show arrows with a bow", true));
    private final BoolSetting durabilityBar = add(new BoolSetting("showDurabilityBar", "Durability bar", false));
    private final ChoiceSetting durabilityType = add(new ChoiceSetting("durabilityType", "Durability", "Percentage", "Percentage", "Value", "None"));
    private final ChoiceSetting layout = add(new ChoiceSetting("displayMode", "Layout", "Vertical", "Vertical", "Horizontal"));
    private final ChoiceSetting alignment = add(new ChoiceSetting("alignment", "Alignment", "Auto", "Auto", "Left", "Center", "Right"));

    private static final class Entry {
        final ItemStack stack;
        final String text;
        final int color, width;

        Entry(ItemStack stack, String text, int color, int width) {
            this.stack = stack;
            this.text = text;
            this.color = color;
            this.width = width;
        }
    }

    private HudContext entriesFor;
    private List<Entry> entriesCache;

    public ArmorStatus() {
        super("armor", "Armor Status", "Your armour and held items with their durability.");
        setPosition(80, 125);
        setEnabled(true);
    }

    private boolean vertical() { return layout.is("Vertical"); }

    private Entry entry(HudContext ctx, ItemStack stack, boolean sample) {
        String text;
        int color;
        if (ItemCounts.hasDurability(stack)) {
            if (durabilityType.is("Percentage")) text = ItemCounts.durabilityPercent(stack) + "%";
            else if (durabilityType.is("Value")) text = Integer.toString(ItemCounts.durability(stack));
            else text = "";
            color = ItemCounts.barColor(stack);
        } else {
            text = Integer.toString(sample ? stack.getMaxStackSize() : ItemCounts.stackCount(stack, ctx.player().inventory));
            color = textColor();
        }
        return new Entry(stack, text, color, ITEM + (text.length() == 0 ? 0 : ctx.textWidth(text) + 1));
    }

    private List<Entry> entries(HudContext ctx) {
        if (entriesFor != ctx) {
            entriesCache = buildEntries(ctx);
            entriesFor = ctx;
        }
        return entriesCache;
    }

    private List<Entry> buildEntries(HudContext ctx) {
        List<Entry> out = new ArrayList<Entry>(7);
        EntityPlayer p = ctx.player();
        if (ctx.level() == null || !ctx.editing() && p == null) return out;
        boolean sample = ctx.editing();
        boolean bow = false;
        for (int i = 0; i < show.length; i++) {
            if (!show[i].get()) continue;
            ItemStack s;
            if (sample) s = new ItemStack(SAMPLE[i]);
            else if (i < 4) s = p.inventory.armorInventory[3 - i];
            else s = p.getHeldItem();
            if (s == null) continue;
            out.add(entry(ctx, s, sample));
            if (i == 4 && s.getItem() == Items.bow) bow = true;
        }
        if (bow && showArrows.get()) {
            int total = sample ? 64 : ItemCounts.itemCount(Items.arrow, p.inventory);
            String text = Integer.toString(total);
            out.add(new Entry(new ItemStack(Items.arrow), text, textColor(), ITEM + ctx.textWidth(text) + 1));
        }
        return out;
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

    private boolean rightSide(HudContext ctx) { return x() + screenWidth(ctx) / 2.0 > ctx.width() / 2.0; }

    @Override
    public void render(Canvas c, HudContext ctx) {
        List<Entry> es = entries(ctx);
        if (es.isEmpty()) {
            if (ctx.editing()) c.text(name(), 0, (ITEM - 8) / 2, textColor(), shadow.get());
            return;
        }
        boolean right = rightSide(ctx);
        boolean inverted = vertical() && right;
        int total = width(ctx);
        int x = 0, y = 0;
        for (Entry e : es) {
            int ex = x;
            if (vertical()) {
                String a = alignment.get();
                if (a.equals("Right") || a.equals("Auto") && right) ex = total - e.width;
                else if (a.equals("Center")) ex = (total - e.width) / 2;
            }
            int textW = e.text.length() == 0 ? 0 : ctx.textWidth(e.text);
            int itemX = inverted ? ex + (textW > 0 ? textW + 1 : 0) : ex;
            int textX = inverted ? ex : ex + ITEM + 1;
            c.item(e.stack, itemX, y);
            if (durabilityBar.get()) c.itemOverlay(e.stack, itemX, y, "");
            if (e.text.length() > 0) c.text(e.text, textX, y + 4, e.color, shadow.get());
            if (vertical()) y += ITEM + V_GAP;
            else x += e.width + H_GAP;
        }
    }
}
