package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** Worn armour (and optionally the held item) with durability readouts. */
public class ArmorStatus extends HudElement {
    private static final int SLOT = 16, GAP = 2;
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final ChoiceSetting layout = add(new ChoiceSetting("layout", "Layout", "Vertical", "Vertical", "Horizontal"));
    private final ChoiceSetting durability = add(new ChoiceSetting("durability", "Durability", "Remaining", "Remaining", "Percent", "Bar only", "None"));
    private final BoolSetting showHand = add(new BoolSetting("showHand", "Include held item", true));
    private final BoolSetting hideEmpty = add(new BoolSetting("hideEmpty", "Hide empty slots", true));
    private final IntSetting warnBelow = add(new IntSetting("warnBelow", "Warn below", 10, 0, 50, 1, "%"));
    private final ColorSetting textColor = add(new ColorSetting("textColor", "Text colour", 0xFFFFFFFF));
    private final ColorSetting warnColor = add(new ColorSetting("warnColor", "Warning colour", 0xFFFF5555));

    public ArmorStatus() {
        super("armor", "Armor Status", "Your armour pieces and how much durability they have left.");
        setPosition(80, 125);
        setEnabled(true);
    }

    private List<ItemStack> stacks(HudContext ctx) {
        List<ItemStack> out = new ArrayList<>(5);
        var p = ctx.player();
        if (p == null) {
            if (ctx.editing()) {
                out.add(new ItemStack(Items.DIAMOND_HELMET));
                out.add(new ItemStack(Items.DIAMOND_CHESTPLATE));
                out.add(new ItemStack(Items.DIAMOND_LEGGINGS));
                out.add(new ItemStack(Items.DIAMOND_BOOTS));
            }
            return out;
        }
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            if (!s.isEmpty() || !hideEmpty.get()) out.add(s);
        }
        if (showHand.get()) {
            ItemStack hand = p.getMainHandItem();
            if (!hand.isEmpty() || !hideEmpty.get()) out.add(hand);
        }
        if (out.isEmpty() && ctx.editing()) {
            out.add(new ItemStack(Items.DIAMOND_HELMET));
            out.add(new ItemStack(Items.DIAMOND_CHESTPLATE));
            out.add(new ItemStack(Items.DIAMOND_LEGGINGS));
            out.add(new ItemStack(Items.DIAMOND_BOOTS));
        }
        return out;
    }

    private String text(ItemStack s) {
        if (s.isEmpty() || !s.isDamageableItem()) return "";
        int left = s.getMaxDamage() - s.getDamageValue();
        return switch (durability.get()) {
            case "Remaining" -> Integer.toString(left);
            case "Percent" -> (left * 100 / Math.max(1, s.getMaxDamage())) + "%";
            default -> "";
        };
    }

    private boolean vertical() {
        return layout.is("Vertical");
    }

    private int textWidth(HudContext ctx, List<ItemStack> stacks) {
        int w = 0;
        for (ItemStack s : stacks) w = Math.max(w, ctx.textWidth(text(s)));
        return w;
    }

    @Override
    public boolean visible(HudContext ctx) {
        return ctx.player() != null && !stacks(ctx).isEmpty();
    }

    @Override
    public int width(HudContext ctx) {
        List<ItemStack> stacks = stacks(ctx);
        if (stacks.isEmpty()) return SLOT;
        if (vertical()) {
            int tw = textWidth(ctx, stacks);
            return SLOT + (tw > 0 ? GAP + tw : 0);
        }
        return stacks.size() * (SLOT + GAP) - GAP;
    }

    @Override
    public int height(HudContext ctx) {
        List<ItemStack> stacks = stacks(ctx);
        if (stacks.isEmpty()) return SLOT;
        if (vertical()) return stacks.size() * (SLOT + GAP) - GAP;
        return SLOT + (durability.is("None") || durability.is("Bar only") ? 0 : GAP + ctx.lineHeight());
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        List<ItemStack> stacks = stacks(ctx);
        int x = 0, y = 0;
        for (ItemStack s : stacks) {
            if (!s.isEmpty()) {
                c.item(s, x, y);
                if (!durability.is("None")) c.itemDecorations(s, x, y);
                String t = text(s);
                if (!t.isEmpty()) {
                    int color = textColor.argb();
                    if (s.isDamageableItem()) {
                        int left = s.getMaxDamage() - s.getDamageValue();
                        if (left * 100 <= warnBelow.get() * Math.max(1, s.getMaxDamage())) color = warnColor.argb();
                    }
                    if (vertical()) c.text(t, x + SLOT + GAP, y + (SLOT - ctx.lineHeight()) / 2 + 1, color, true);
                    else c.centeredText(t, x + SLOT / 2, y + SLOT + GAP, color, true);
                }
            }
            if (vertical()) y += SLOT + GAP; else x += SLOT + GAP;
        }
    }
}
