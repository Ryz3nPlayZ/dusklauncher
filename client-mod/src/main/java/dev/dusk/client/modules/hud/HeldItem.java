package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Icon + name of the item in your main hand. */
public class HeldItem extends HudElement {
    private static final int SLOT = 16, GAP = 3;

    private final BoolSetting showName = add(new BoolSetting("showName", "Show name", true));
    private final BoolSetting showCount = add(new BoolSetting("showCount", "Show count / durability", true));
    private final ColorSetting textColor = add(new ColorSetting("textColor", "Text colour", 0xFFFFFFFF));

    public HeldItem() {
        super("helditem", "Held Item", "The item in your main hand.");
        setPosition(150, 130);
    }

    private ItemStack stack(HudContext ctx) {
        var p = ctx.player();
        ItemStack s = p == null ? ItemStack.EMPTY : p.getMainHandItem();
        if (s.isEmpty() && ctx.editing()) s = new ItemStack(Items.DIAMOND_SWORD);
        return s;
    }

    private String label(ItemStack s) {
        if (!showName.get()) return "";
        String name = s.getHoverName().getString();
        if (showCount.get()) {
            if (s.getCount() > 1) name += " x" + s.getCount();
            else if (s.isDamageableItem()) name += " (" + (s.getMaxDamage() - s.getDamageValue()) + "/" + s.getMaxDamage() + ")";
        }
        return name;
    }

    @Override
    public boolean visible(HudContext ctx) {
        return ctx.player() != null && !stack(ctx).isEmpty();
    }

    @Override
    public int width(HudContext ctx) {
        String l = label(stack(ctx));
        return SLOT + (l.isEmpty() ? 0 : GAP + ctx.textWidth(l));
    }

    @Override
    public int height(HudContext ctx) {
        return SLOT;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        ItemStack s = stack(ctx);
        if (s.isEmpty()) return;
        c.item(s, 0, 0);
        c.itemDecorations(s, 0, 0);
        String l = label(s);
        if (!l.isEmpty()) c.text(l, SLOT + GAP, (SLOT - ctx.lineHeight()) / 2 + 1, textColor.argb(), true);
    }
}
