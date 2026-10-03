package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.modules.misc.HypixelTweaks;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hypixel SkyBlock's health, defence and mana as bars, read off the action
 * bar SkyBlock already sends (the way SkyBlock HUD mods do it), so they stay
 * visible while the action bar shows something else.
 */
public class SkyblockStats extends HudElement {
    private static final int BAR_W = 90, BAR_H = 5, ROW = 12;
    private static final Pattern HEALTH = Pattern.compile("([\\d,]+)/([\\d,]+)❤");
    private static final Pattern DEFENSE = Pattern.compile("([\\d,]+)❈ Defense");
    private static final Pattern MANA = Pattern.compile("([\\d,]+)/([\\d,]+)✎");
    /** The action bar stops repeating stats outside SkyBlock; older readings then hide. */
    private static final long STALE_MS = 5_000;

    private static SkyblockStats instance;

    private final BoolSetting showHealth = add(new BoolSetting("health", "Health", true), "Show");
    private final BoolSetting showDefense = add(new BoolSetting("defense", "Defense", true), "Show");
    private final BoolSetting showMana = add(new BoolSetting("mana", "Mana", true), "Show");
    private final BoolSetting shadow = add(new BoolSetting("shadow", "Text shadow", true));

    private int health = -1, maxHealth, defense = -1, mana = -1, maxMana;
    private long seenAt;

    public SkyblockStats() {
        super("skyblockstats", "SkyBlock Stats", "Hypixel SkyBlock health, defence and mana as bars, from the action bar.");
        setPosition(10, 160);
        instance = this;
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            SkyblockStats m = instance;
            if (!overlay || m == null || !m.enabled() || !HypixelTweaks.onHypixel()) return;
            m.read(message.getString());
        });
    }

    private void read(String bar) {
        boolean any = false;
        Matcher h = HEALTH.matcher(bar);
        if (h.find()) { health = num(h.group(1)); maxHealth = num(h.group(2)); any = true; }
        Matcher d = DEFENSE.matcher(bar);
        if (d.find()) { defense = num(d.group(1)); any = true; }
        Matcher mm = MANA.matcher(bar);
        if (mm.find()) { mana = num(mm.group(1)); maxMana = num(mm.group(2)); any = true; }
        if (any) seenAt = System.currentTimeMillis();
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean fresh() {
        return seenAt != 0 && System.currentTimeMillis() - seenAt < STALE_MS;
    }

    private int rows(HudContext ctx) {
        boolean live = fresh() && !ctx.editing();
        int n = 0;
        if (showHealth.get() && (ctx.editing() || live && health >= 0)) n++;
        if (showDefense.get() && (ctx.editing() || live && defense >= 0)) n++;
        if (showMana.get() && (ctx.editing() || live && mana >= 0)) n++;
        return n;
    }

    @Override
    public boolean visible(HudContext ctx) {
        return ctx.player() != null && rows(ctx) > 0;
    }

    @Override
    public int width(HudContext ctx) {
        return BAR_W;
    }

    @Override
    public int height(HudContext ctx) {
        return Math.max(1, rows(ctx)) * ROW;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        boolean sample = ctx.editing() && !fresh();
        int y = 0;
        if (showHealth.get() && (sample || health >= 0)) {
            y = row(c, y, sample ? "820/1000 ❤" : health + "/" + maxHealth + " ❤", sample ? 0.82f : ratio(health, maxHealth), 0xFFFF5555);
        }
        if (showDefense.get() && (sample || defense >= 0)) {
            y = row(c, y, (sample ? 250 : defense) + " ❈ Defense", -1, 0xFF55FF55);
        }
        if (showMana.get() && (sample || mana >= 0)) {
            row(c, y, sample ? "140/300 ✎" : mana + "/" + maxMana + " ✎", sample ? 0.47f : ratio(mana, maxMana), 0xFF55FFFF);
        }
    }

    private static float ratio(int v, int max) {
        return max <= 0 ? 0 : Math.min(1f, (float) v / max);
    }

    /** A label over a thin bar; a negative fill draws the label alone. */
    private int row(Canvas c, int y, String label, float fill, int color) {
        c.text(label, 0, y, color, shadow.get());
        if (fill >= 0) {
            c.fill(0, y + 9, BAR_W, y + 9 + BAR_H - 3, 0x80000000);
            c.fill(0, y + 9, Math.round(BAR_W * fill), y + 9 + BAR_H - 3, color);
        }
        return y + ROW;
    }
}
