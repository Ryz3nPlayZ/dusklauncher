package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.ItemCounts;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.ChoiceSetting;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

/**
 * Flex-HUD's Held Item: the item in your hand with its durability, or
 * "in this stack / in your inventory" for stackables. Text goes on the item's
 * left on the right half of the screen.
 */
public class HeldItem extends TextHud {
    private static final int ITEM = 16, GAP = 2;

    private final ChoiceSetting durabilityType = add(new ChoiceSetting("durabilityType", "Durability", "Percentage", "Percentage", "Value", "None"));

    private ItemStack stack;
    private int labelColor;

    public HeldItem() {
        super("helditem", "Held Item", "The item in your hand and how much of it you have.");
        setPosition(150, 170);
    }

    @Override
    protected String text(HudContext ctx) {
        EntityPlayerSP p = ctx.player();
        if (p == null || ctx.level() == null) return null;
        if (ctx.editing()) return sample();
        stack = p.getHeldItem();
        if (stack == null) return null;
        if (ItemCounts.hasDurability(stack)) {
            labelColor = ItemCounts.barColor(stack);
            if (durabilityType.is("Percentage")) return ItemCounts.durabilityPercent(stack) + "%";
            if (durabilityType.is("Value")) return ItemCounts.durability(stack) + "/" + stack.getMaxDamage();
            return "";
        }
        labelColor = textColor();
        return stack.stackSize + "/" + ItemCounts.stackCount(stack, p.inventory);
    }

    @Override
    protected String sample() {
        stack = new ItemStack(Blocks.wool, 64, 14);
        labelColor = textColor();
        return "64/256";
    }

    @Override
    public int width(HudContext ctx) {
        String v = current(ctx);
        return v == null || v.isEmpty() ? ITEM : ITEM + GAP + ctx.textWidth(v);
    }

    @Override
    public int height(HudContext ctx) { return ITEM; }

    @Override
    public void render(Canvas c, HudContext ctx) {
        String v = current(ctx);
        if (v == null || stack == null) return;
        if (x() + screenWidth(ctx) / 2.0 > ctx.width() / 2.0) {
            c.text(v, 0, 4, labelColor, shadow.get());
            c.item(stack, v.isEmpty() ? 0 : ctx.textWidth(v) + GAP, 0);
        } else {
            c.item(stack, 0, 0);
            c.text(v, ITEM + GAP, 4, labelColor, shadow.get());
        }
    }
}
