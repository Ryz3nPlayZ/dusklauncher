package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.util.Fmt;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.resources.I18n;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** Icon, "Name N" and a timer that pulses in the last ten seconds. */
public class PotionEffects extends TextHud {
    private static final int ICON = 18, TEXT_GAP = 1, TEXT_ICON_GAP = 2, EFFECT_GAP = 6;

    private final ChoiceSetting alignment = add(new ChoiceSetting("alignment", "Alignment", "Auto", "Auto", "Left", "Center", "Right"));
    private final ChoiceSetting iconPlacement = add(new ChoiceSetting("iconPlacement", "Icon placement", "Right", "Top", "Right", "Bottom", "Left"));

    private static final class Entry {
        final PotionEffect effect;
        final String name, duration;
        final int textWidth;

        Entry(PotionEffect effect, String name, String duration, int textWidth) {
            this.effect = effect;
            this.name = name;
            this.duration = duration;
            this.textWidth = textWidth;
        }
    }

    private HudContext entriesFor;
    private List<Entry> entriesCache;

    public PotionEffects() {
        super("effects", "Potion Effects", "Your active status effects and their remaining time.");
        setPosition(380, 60);
        setEnabled(true);
    }

    private List<Entry> entries(HudContext ctx) {
        if (entriesFor != ctx) {
            entriesCache = buildEntries(ctx);
            entriesFor = ctx;
        }
        return entriesCache;
    }

    private List<Entry> buildEntries(HudContext ctx) {
        Collection<PotionEffect> effects;
        EntityPlayerSP p = ctx.player();
        if (p == null || ctx.editing() && p.getActivePotionEffects().isEmpty()) {
            effects = Arrays.asList(new PotionEffect(Potion.moveSpeed.id, 1800, 1),
                    new PotionEffect(Potion.fireResistance.id, 200));
        } else {
            effects = p.getActivePotionEffects();
        }
        List<Entry> out = new ArrayList<Entry>();
        for (PotionEffect e : effects) {
            Potion potion = Potion.potionTypes[e.getPotionID()];
            if (potion == null) continue;
            String name = I18n.format(potion.getName()) + " " + (e.getAmplifier() + 1);
            String duration = e.getIsPotionDurationMax() ? "∞" : duration(e.getDuration() / 20);
            out.add(new Entry(e, name, duration, Math.max(ctx.textWidth(name), ctx.textWidth(duration))));
        }
        return out;
    }

    private static String duration(int s) {
        StringBuilder sb = new StringBuilder(8);
        if (s >= 3600) Fmt.pad2(sb, s / 3600).append(':');
        Fmt.pad2(sb, s < 3600 ? s / 60 : s % 3600 / 60).append(':');
        return Fmt.pad2(sb, s % 60).toString();
    }

    private boolean stacked() { return iconPlacement.is("Top") || iconPlacement.is("Bottom"); }

    private int entryWidth(Entry e) {
        return stacked() ? Math.max(e.textWidth, ICON) : e.textWidth + TEXT_ICON_GAP + ICON;
    }

    private int entryHeight(HudContext ctx) {
        int lines = ctx.lineHeight() * 2 + TEXT_GAP;
        return stacked() ? ICON + TEXT_ICON_GAP + lines : Math.max(ICON, lines);
    }

    @Override
    protected String text(HudContext ctx) {
        EntityPlayerSP p = ctx.player();
        return p == null || p.getActivePotionEffects().isEmpty() ? null : "";
    }

    @Override
    public int width(HudContext ctx) {
        int w = 0;
        for (Entry e : entries(ctx)) w = Math.max(w, entryWidth(e));
        return w;
    }

    @Override
    public int height(HudContext ctx) {
        int n = entries(ctx).size();
        return n == 0 ? 0 : n * entryHeight(ctx) + (n - 1) * EFFECT_GAP;
    }

    private static int timerAlpha(HudContext ctx, int ticksLeft) {
        if (ctx.mc().isGamePaused() || ctx.level() == null) return 255;
        float cycle = (ctx.level().getTotalWorldTime() % 20 + ctx.partialTick()) / 20f;
        float a = (float) (Math.sin(cycle * Math.PI * 2) * 0.5 + 0.5);
        if (ticksLeft <= 100) return (int) (a * 255);
        if (ticksLeft <= 200) return (int) ((a * 0.5f + 0.5f) * 255);
        return 255;
    }

    private int shift(HudContext ctx, int total, int w) {
        String a = alignment.get();
        boolean right = x() + screenWidth(ctx) / 2.0 > ctx.width() / 2.0;
        if (a.equals("Right") || a.equals("Auto") && right) return total - w;
        if (a.equals("Center")) return total / 2 - w / 2;
        return 0;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        List<Entry> list = entries(ctx);
        int total = width(ctx), lh = ctx.lineHeight(), color = textColor();
        boolean sh = shadow.get();
        int y = 0;
        for (Entry e : list) {
            Potion potion = Potion.potionTypes[e.effect.getPotionID()];
            int durColor = e.effect.getIsPotionDurationMax() ? color
                    : Math.max(4, timerAlpha(ctx, e.effect.getDuration())) << 24 | color & 0xFFFFFF;
            String place = iconPlacement.get();
            if (place.equals("Top")) {
                int tx = shift(ctx, total, e.textWidth);
                c.text(e.name, tx, y + ICON + TEXT_ICON_GAP, color, sh);
                c.text(e.duration, tx, y + ICON + TEXT_ICON_GAP + lh + TEXT_GAP, durColor, sh);
                c.effectIcon(potion, shift(ctx, total, ICON), y, ICON);
            } else if (place.equals("Bottom")) {
                int tx = shift(ctx, total, e.textWidth);
                c.text(e.name, tx, y, color, sh);
                c.text(e.duration, tx, y + lh + TEXT_GAP, durColor, sh);
                c.effectIcon(potion, shift(ctx, total, ICON), y + lh * 2 + TEXT_GAP + TEXT_ICON_GAP, ICON);
            } else if (place.equals("Left")) {
                int x = shift(ctx, total, entryWidth(e));
                c.effectIcon(potion, x, y, ICON);
                c.text(e.name, x + ICON + TEXT_ICON_GAP, y, color, sh);
                c.text(e.duration, x + ICON + TEXT_ICON_GAP, y + lh + TEXT_GAP, durColor, sh);
            } else {
                int x = shift(ctx, total, entryWidth(e));
                c.text(e.name, x, y, color, sh);
                c.text(e.duration, x, y + lh + TEXT_GAP, durColor, sh);
                c.effectIcon(potion, x + e.textWidth + TEXT_ICON_GAP, y, ICON);
            }
            y += entryHeight(ctx) + EFFECT_GAP;
        }
    }
}
