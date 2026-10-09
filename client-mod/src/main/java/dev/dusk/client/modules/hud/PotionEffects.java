package dev.dusk.client.modules.hud;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.ChoiceSetting;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Flex-HUD's potion effects: icon, "Name N" and a timer that pulses in the last ten seconds. */
public class PotionEffects extends TextHud {
    private static final int ICON = 18, TEXT_GAP = 1, TEXT_ICON_GAP = 2, EFFECT_GAP = 6;

    private final ChoiceSetting alignment = add(new ChoiceSetting("alignment", "Alignment", "Auto", "Auto", "Left", "Center", "Right"));
    private final ChoiceSetting iconPlacement = add(new ChoiceSetting("iconPlacement", "Icon placement", "Right", "Top", "Right", "Bottom", "Left"));

    public PotionEffects() {
        super("effects", "Potion Effects", "Your active status effects and their remaining time.");
        setPosition(380, 60);
        setEnabled(true);
    }

    /** One effect's lines and where its text and icon groups sit before alignment. */
    private record Entry(MobEffectInstance effect, String name, String duration, int textWidth) {}

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
        List<MobEffectInstance> effects;
        if (ctx.player() == null || ctx.editing() && ctx.player().getActiveEffects().isEmpty()) {
            effects = List.of(new MobEffectInstance(Compat.speedEffect(), 1800, 1),
                    new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200));
        } else {
            effects = new ArrayList<>(ctx.player().getActiveEffects());
            Collections.sort(effects);
        }
        List<Entry> out = new ArrayList<>();
        for (MobEffectInstance e : effects) {
            String name = e.getEffect().value().getDisplayName().getString() + " " + (e.getAmplifier() + 1);
            String duration = e.isInfiniteDuration() ? "∞" : duration(e.getDuration() / 20);
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

    private boolean stacked() {
        return iconPlacement.is("Top") || iconPlacement.is("Bottom");
    }

    private int entryWidth(Entry e) {
        return stacked() ? Math.max(e.textWidth(), ICON) : e.textWidth() + TEXT_ICON_GAP + ICON;
    }

    private int entryHeight(HudContext ctx) {
        int lines = ctx.lineHeight() * 2 + TEXT_GAP;
        return stacked() ? ICON + TEXT_ICON_GAP + lines : Math.max(ICON, lines);
    }

    @Override
    protected String text(HudContext ctx) {
        var p = ctx.player();
        return p == null || p.getActiveEffects().isEmpty() ? null : "";
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

    /** Timer alpha: a soft pulse under ten seconds, a full one under five. */
    private static int timerAlpha(HudContext ctx, int ticksLeft) {
        if (ctx.mc().isPaused() || ctx.level() == null) return 255;
        float cycle = (Compat.dayTime(ctx.level()) % 20 + ctx.partialTick()) / 20f;
        float a = (float) (Math.sin(cycle * Math.PI * 2) * 0.5 + 0.5);
        if (ticksLeft <= 100) return (int) (a * 255);
        if (ticksLeft <= 200) return (int) ((a * 0.5f + 0.5f) * 255);
        return 255;
    }

    /** How far a group {@code w} wide moves right inside the {@code total} wide box. */
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
            int durColor = e.effect().isInfiniteDuration() ? color
                    // older fonts draw alpha under 4 as opaque, so the pulse bottoms out there
                    : Math.max(4, timerAlpha(ctx, e.effect().getDuration())) << 24 | color & 0xFFFFFF;
            switch (iconPlacement.get()) {
                case "Top" -> {
                    int tx = shift(ctx, total, e.textWidth());
                    c.text(e.name(), tx, y + ICON + TEXT_ICON_GAP, color, sh);
                    c.text(e.duration(), tx, y + ICON + TEXT_ICON_GAP + lh + TEXT_GAP, durColor, sh);
                    c.effectIcon(e.effect().getEffect(), shift(ctx, total, ICON), y, ICON);
                }
                case "Bottom" -> {
                    int tx = shift(ctx, total, e.textWidth());
                    c.text(e.name(), tx, y, color, sh);
                    c.text(e.duration(), tx, y + lh + TEXT_GAP, durColor, sh);
                    c.effectIcon(e.effect().getEffect(), shift(ctx, total, ICON), y + lh * 2 + TEXT_GAP + TEXT_ICON_GAP, ICON);
                }
                case "Left" -> {
                    int x = shift(ctx, total, entryWidth(e));
                    c.effectIcon(e.effect().getEffect(), x, y, ICON);
                    c.text(e.name(), x + ICON + TEXT_ICON_GAP, y, color, sh);
                    c.text(e.duration(), x + ICON + TEXT_ICON_GAP, y + lh + TEXT_GAP, durColor, sh);
                }
                default -> {
                    int x = shift(ctx, total, entryWidth(e));
                    c.text(e.name(), x, y, color, sh);
                    c.text(e.duration(), x, y + lh + TEXT_GAP, durColor, sh);
                    c.effectIcon(e.effect().getEffect(), x + e.textWidth() + TEXT_ICON_GAP, y, ICON);
                }
            }
            y += entryHeight(ctx) + EFFECT_GAP;
        }
    }
}
