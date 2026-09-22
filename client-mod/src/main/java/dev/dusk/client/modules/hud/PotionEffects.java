package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Active status effects as "Name II  1:23" rows with a colour swatch. */
public class PotionEffects extends HudElement {
    private static final int ROW = 11, SWATCH = 8;
    private static final String[] ROMAN = {"", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private final BoolSetting showDuration = add(new BoolSetting("showDuration", "Show duration", true));
    private final BoolSetting hideAmbient = add(new BoolSetting("hideAmbient", "Hide beacon effects", false));
    private final ColorSetting textColor = add(new ColorSetting("textColor", "Text colour", 0xFFFFFFFF));
    private final ColorSetting timeColor = add(new ColorSetting("timeColor", "Duration colour", 0xFFAAAAAA));

    public PotionEffects() {
        super("effects", "Potion Effects", "Your active status effects and their remaining time.");
        setPosition(150, 150);
        setEnabled(true);
    }

    private List<MobEffectInstance> effects(HudContext ctx) {
        var p = ctx.player();
        List<MobEffectInstance> out = new ArrayList<>();
        if (p != null) {
            for (MobEffectInstance e : p.getActiveEffects()) {
                if (hideAmbient.get() && e.isAmbient()) continue;
                out.add(e);
            }
            out.sort(Comparator.comparingInt(MobEffectInstance::getDuration).reversed());
        }
        return out;
    }

    private String name(MobEffectInstance e) {
        String n = e.getEffect().value().getDisplayName().getString();
        int amp = e.getAmplifier();
        return amp > 0 && amp < ROMAN.length ? n + " " + ROMAN[amp] : n;
    }

    private String time(MobEffectInstance e) {
        if (!showDuration.get()) return "";
        return e.isInfiniteDuration() ? "**:**" : MobEffectUtil.formatDuration(e, 1f, 20f).getString();
    }

    @Override
    public boolean visible(HudContext ctx) {
        return ctx.player() != null && !effects(ctx).isEmpty();
    }

    @Override
    public int width(HudContext ctx) {
        List<MobEffectInstance> list = effects(ctx);
        if (list.isEmpty()) return ctx.textWidth("Speed II  1:30") + SWATCH + 4;
        int w = 0;
        for (MobEffectInstance e : list) {
            String t = time(e);
            w = Math.max(w, ctx.textWidth(name(e)) + (t.isEmpty() ? 0 : 6 + ctx.textWidth(t)));
        }
        return w + SWATCH + 4;
    }

    @Override
    public int height(HudContext ctx) {
        return Math.max(1, effects(ctx).size()) * ROW - 1;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        List<MobEffectInstance> list = effects(ctx);
        if (list.isEmpty()) {
            if (!ctx.editing()) return;
            c.fill(0, 1, SWATCH, 1 + SWATCH, 0xFF7CAFC6);
            c.text("Speed II", SWATCH + 4, 1, textColor.argb(), true);
            c.text("1:30", SWATCH + 4 + ctx.textWidth("Speed II") + 6, 1, timeColor.argb(), true);
            return;
        }
        int y = 0;
        for (MobEffectInstance e : list) {
            int color = 0xFF000000 | e.getEffect().value().getColor();
            c.fill(0, y + 1, SWATCH, y + 1 + SWATCH, color);
            String n = name(e);
            c.text(n, SWATCH + 4, y + 1, textColor.argb(), true);
            String t = time(e);
            if (!t.isEmpty()) c.text(t, SWATCH + 4 + ctx.textWidth(n) + 6, y + 1, timeColor.argb(), true);
            y += ROW;
        }
    }
}
