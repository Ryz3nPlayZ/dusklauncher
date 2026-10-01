package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.ItemCounts;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.ChoiceSetting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Flex-HUD's Held Item: the item in your main hand with its durability, or
 * "in this stack / in your inventory" for stackables. Text goes on the item's
 * left on the right half of the screen.
 */
public class HeldItem extends TextHud {
    private static final int ITEM = 16, GAP = 2;

    private final ChoiceSetting durabilityType = add(new ChoiceSetting("durabilityType", "Durability", "Percentage", "Percentage", "Value", "None"));

    private ItemStack stack = ItemStack.EMPTY;
    private int labelColor;

    public HeldItem() {
        super("helditem", "Held Item", "The item in your main hand and how much of it you have.");
        setPosition(150, 170);
    }

    @Override
    protected String text(HudContext ctx) {
        var p = ctx.player();
        if (p == null || ctx.level() == null) return null;
        if (ctx.editing()) return sample();
        stack = p.getMainHandItem();
        if (stack.isEmpty()) return null;
        if (ItemCounts.hasDurability(stack)) {
            labelColor = ItemCounts.barColor(stack);
            return switch (durabilityType.get()) {
                case "Percentage" -> ItemCounts.durabilityPercent(stack) + "%";
                case "Value" -> ItemCounts.durability(stack) + "/" + stack.getMaxDamage();
                default -> "";
            };
        }
        labelColor = textColor();
        return stack.getCount() + "/" + ItemCounts.stackCount(stack, p.getInventory());
    }

    @Override
    protected String sample() {
        stack = new ItemStack(Items.DIAMOND_BLOCK);
        labelColor = textColor();
        return "64/256";
    }

    @Override
    public int width(HudContext ctx) {
        String v = current(ctx);
        if (v == null) return ITEM;
        return v.isEmpty() ? ITEM : ITEM + GAP + ctx.textWidth(v);
    }

    @Override
    public int height(HudContext ctx) {
        return ITEM;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        String v = current(ctx);
        if (v == null || stack.isEmpty()) return;
        boolean right = x() + screenWidth(ctx) / 2.0 > ctx.width() / 2.0;
        if (right) {
            c.text(v, 0, 4, labelColor, shadow.get());
            c.item(stack, v.isEmpty() ? 0 : ctx.textWidth(v) + GAP, 0);
        } else {
            c.item(stack, 0, 0);
            c.text(v, ITEM + GAP, 4, labelColor, shadow.get());
        }
    }
}
