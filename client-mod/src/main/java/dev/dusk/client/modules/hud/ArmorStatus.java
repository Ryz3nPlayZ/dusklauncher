package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.ItemCounts;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Flex-HUD's Armor Status: each worn piece and both hands with its durability
 * (in the durability bar's colour) or, for stackables, how many you carry;
 * plus your arrows while a bow or crossbow is in hand. On the right half of
 * the screen the text goes before the item.
 */
public class ArmorStatus extends TextHud {
    private static final int ITEM = 16, V_GAP = 1, H_GAP = 4;
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
    private static final Item[] SAMPLE = {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS,
            Items.DIAMOND_BOOTS, Items.BOW, Items.SHIELD};
    private static final Item[] ARROWS = {Items.ARROW, Items.SPECTRAL_ARROW, Items.TIPPED_ARROW};

    private final BoolSetting[] show = {
            add(new BoolSetting("showHelmet", "Show helmet", true)),
            add(new BoolSetting("showChestplate", "Show chestplate", true)),
            add(new BoolSetting("showLeggings", "Show leggings", true)),
            add(new BoolSetting("showBoots", "Show boots", true)),
            add(new BoolSetting("showHeldItem", "Show held item", true)),
            add(new BoolSetting("showOffHandItem", "Show off-hand item", true)),
    };
    private final BoolSetting showArrows = add(new BoolSetting("showArrows", "Show arrows with a bow", true));
    private final BoolSetting separateArrows = add(new BoolSetting("separateArrowTypes", "Separate arrow types", false));
    private final BoolSetting durabilityBar = add(new BoolSetting("showDurabilityBar", "Durability bar", false));
    private final ChoiceSetting durabilityType = add(new ChoiceSetting("durabilityType", "Durability", "Percentage", "Percentage", "Value", "None"));
    private final ChoiceSetting layout = add(new ChoiceSetting("displayMode", "Layout", "Vertical", "Vertical", "Horizontal"));
    private final ChoiceSetting alignment = add(new ChoiceSetting("alignment", "Alignment", "Auto", "Auto", "Left", "Center", "Right"));

    private record Entry(ItemStack stack, String text, int color, int width) {}

    public ArmorStatus() {
        super("armor", "Armor Status", "Your armour and held items with their durability.");
        setPosition(80, 125);
        setEnabled(true);
    }

    private boolean vertical() {
        return layout.is("Vertical");
    }

    private Entry entry(HudContext ctx, ItemStack stack, boolean sample) {
        String text;
        int color;
        if (ItemCounts.hasDurability(stack)) {
            text = switch (durabilityType.get()) {
                case "Percentage" -> ItemCounts.durabilityPercent(stack) + "%";
                case "Value" -> Integer.toString(ItemCounts.durability(stack));
                default -> "";
            };
            color = ItemCounts.barColor(stack);
        } else {
            text = Integer.toString(sample ? stack.getMaxStackSize() : ItemCounts.stackCount(stack, ctx.player().getInventory()));
            color = textColor();
        }
        return new Entry(stack, text, color, ITEM + (text.isEmpty() ? 0 : ctx.textWidth(text) + 1));
    }

    private Entry arrows(HudContext ctx, Item item, int count) {
        String text = Integer.toString(count);
        return new Entry(new ItemStack(item), text, textColor(), ITEM + ctx.textWidth(text) + 1);
    }

    /** Entries for the frame {@link #entriesFor} was built for: width, height, clamping and drawing each ask. */
    private HudContext entriesFor;
    private List<Entry> entriesCache;

    private List<Entry> entries(HudContext ctx) {
        if (entriesFor != ctx) {
            entriesCache = buildEntries(ctx);
            entriesFor = ctx;
        }
        return entriesCache;
    }

    private List<Entry> buildEntries(HudContext ctx) {
        List<Entry> out = new ArrayList<>(9);
        if (ctx.level() == null || !ctx.editing() && ctx.player() == null) return out;
        boolean sample = ctx.editing();
        boolean bow = false;
        for (int i = 0; i < SLOTS.length; i++) {
            if (!show[i].get()) continue;
            ItemStack s = sample ? new ItemStack(SAMPLE[i]) : ctx.player().getItemBySlot(SLOTS[i]);
            if (s.isEmpty()) continue;
            out.add(entry(ctx, s, sample));
            if (i >= 4 && (s.is(Items.BOW) || s.is(Items.CROSSBOW))) bow = true;
        }
        if (bow && showArrows.get()) {
            if (separateArrows.get()) {
                for (Item a : ARROWS) {
                    out.add(arrows(ctx, a, sample ? 64 : ItemCounts.itemCount(a, ctx.player().getInventory())));
                }
            } else {
                int total = 0;
                if (sample) total = 64;
                else for (Item a : ARROWS) total += ItemCounts.itemCount(a, ctx.player().getInventory());
                out.add(arrows(ctx, Items.ARROW, total));
            }
        }
        return out;
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

    /** Whether the box sits on the right half of the screen, where Flex flips the text to the item's left. */
    private boolean rightSide(HudContext ctx) {
        return x() + screenWidth(ctx) / 2.0 > ctx.width() / 2.0;
    }

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
            int textW = e.text.isEmpty() ? 0 : ctx.textWidth(e.text);
            int itemX = inverted ? ex + (textW > 0 ? textW + 1 : 0) : ex;
            int textX = inverted ? ex : ex + ITEM + 1;
            c.item(e.stack, itemX, y);
            if (durabilityBar.get()) c.itemDecorations(e.stack, itemX, y);
            if (!e.text.isEmpty()) c.text(e.text, textX, y + 4, e.color, shadow.get());
            if (vertical()) y += ITEM + V_GAP;
            else x += e.width + H_GAP;
        }
    }
}
