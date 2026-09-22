package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Shield readiness: green when it can block, orange while raising it
 * (the first 5 ticks of use do not block) and red while on cooldown after
 * an axe disable.
 */
public class ShieldStatus extends HudElement {
    private static final int SLOT = 16, GAP = 3, BAR_H = 3;
    private static final int RAISE_TICKS = 5;

    private final BoolSetting showText = add(new BoolSetting("showText", "Show status text", true));
    private final BoolSetting onlyHolding = add(new BoolSetting("onlyHolding", "Only while holding a shield", true));
    private final ColorSetting readyColor = add(new ColorSetting("readyColor", "Ready colour", 0xFF55FF55));
    private final ColorSetting raisingColor = add(new ColorSetting("raisingColor", "Raising colour", 0xFFFFAA00));
    private final ColorSetting cooldownColor = add(new ColorSetting("cooldownColor", "Cooldown colour", 0xFFFF5555));

    public ShieldStatus() {
        super("shield", "Shield Status", "Whether your shield can block right now: ready, raising or on cooldown.");
        setPosition(150, 200);
    }

    private ItemStack shield(HudContext ctx) {
        var p = ctx.player();
        if (p == null) return ItemStack.EMPTY;
        if (p.getOffhandItem().getItem() == Items.SHIELD) return p.getOffhandItem();
        if (p.getMainHandItem().getItem() == Items.SHIELD) return p.getMainHandItem();
        return ItemStack.EMPTY;
    }

    private record State(int color, String text, float fraction) {}

    private State state(HudContext ctx) {
        var p = ctx.player();
        ItemStack s = shield(ctx);
        if (p == null || s.isEmpty()) return new State(readyColor.argb(), "Ready", 1f);
        if (p.getCooldowns().isOnCooldown(s)) {
            float pct = p.getCooldowns().getCooldownPercent(s, ctx.partialTick());
            return new State(cooldownColor.argb(), "Cooldown " + Fmt.fixed(pct * 5, 1) + "s", 1 - pct);
        }
        if (p.isUsingItem() && p.getUseItem() == s) {
            int t = p.getTicksUsingItem();
            if (t < RAISE_TICKS) return new State(raisingColor.argb(), "Raising", t / (float) RAISE_TICKS);
            return new State(readyColor.argb(), "Blocking", 1f);
        }
        return new State(readyColor.argb(), "Ready", 1f);
    }

    @Override
    public boolean visible(HudContext ctx) {
        return ctx.player() != null && (!onlyHolding.get() || !shield(ctx).isEmpty());
    }

    @Override
    public int width(HudContext ctx) {
        return SLOT + (showText.get() ? GAP + ctx.textWidth("Cooldown 5.0s") : 0);
    }

    @Override
    public int height(HudContext ctx) {
        return SLOT + 1 + BAR_H;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        ItemStack s = shield(ctx);
        State st = state(ctx);
        c.item(s.isEmpty() ? new ItemStack(Items.SHIELD) : s, 0, 0);
        if (showText.get()) c.text(st.text(), SLOT + GAP, (SLOT - ctx.lineHeight()) / 2 + 1, st.color(), true);
        int w = width(ctx);
        c.fill(0, SLOT + 1, w, SLOT + 1 + BAR_H, 0x80000000);
        c.fill(0, SLOT + 1, Math.round(w * st.fraction()), SLOT + 1 + BAR_H, st.color());
    }
}
