package dev.dusk.client.modules.render;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.KeySetting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Gamma Utils' gamma controls (behaviour only; that mod is LGPL — see
 * NOTICE): G flips between the normal gamma and a toggled one (1500%),
 * the arrow keys step it by 10% between -750% and 1500%, and each change
 * shows "Gamma: N%" on the action bar, gold above 100% and red below 0%.
 * Off, the options slider's own gamma is used. Only the light texture is
 * touched, so it is a pure rendering change.
 */
public class Fullbright extends Module {
    private static final int MIN = -750, MAX = 1500;
    private static final int DEFAULT_COLOR = 0x00AA00, POSITIVE_COLOR = 0xFFAA00, NEGATIVE_COLOR = 0xAA0000;

    private static Fullbright instance;

    private final KeySetting toggleKey = add(new KeySetting("fullbright", "Toggle key", InputConstants.KEY_G));
    private final KeySetting upKey = add(new KeySetting("gamma_up", "Increase key", InputConstants.KEY_UP));
    private final KeySetting downKey = add(new KeySetting("gamma_down", "Decrease key", InputConstants.KEY_DOWN));
    private final IntSetting brightness = add(new IntSetting("brightness", "Toggled gamma", 1500, MIN, MAX, 10, "%"));
    private final IntSetting step = add(new IntSetting("step", "Gamma step", 10, 10, 500, 10, "%"));
    private final BoolSetting updateToggle = add(new BoolSetting("updateToggle", "Remember adjusted gamma", false));
    private final BoolSetting hudMessage = add(new BoolSetting("hudMessage", "Show gamma message", true));
    private final BoolSetting smooth = add(new BoolSetting("smoothTransition", "Smooth transition", false));
    private final IntSetting transitionSpeed = add(new IntSetting("transitionSpeed", "Transition speed", 3000, 100, 10000, 100, "%/s"));

    /** Gamma in percent while on; starts at the toggled value and moves with the arrow keys. */
    private int level;
    /** The toggled value {@link #level} was last reset to; a new one (from the menu) resets it again. */
    private int levelFrom = Integer.MIN_VALUE;
    /** What the lightmap was last given, for smooth transitions; NaN when vanilla's own gamma is used. */
    private double shown = Double.NaN;
    private long lastFrameNanos;

    public Fullbright() {
        super("fullbright", "Fullbright", Category.RENDER, "Night vision without the potion: boosts gamma beyond 100%.");
        instance = this;
    }

    public static Fullbright instance() {
        return instance;
    }

    /** Handles the three keys; true when a setting changed. */
    public boolean tickKeys() {
        boolean changed = false;
        while (toggleKey.mapping().consumeClick()) {
            setEnabled(!enabled());
            showMessage();
            changed = true;
        }
        while (upKey.mapping().consumeClick()) {
            adjust(1);
            changed = true;
        }
        while (downKey.mapping().consumeClick()) {
            adjust(-1);
            changed = true;
        }
        return changed;
    }

    @Override
    protected void onEnable() {
        levelFrom = Integer.MIN_VALUE;
    }

    /** One gamma step up or down; from off it starts at the current vanilla gamma and turns on. */
    public void adjust(int direction) {
        int from = enabled() ? level() : vanillaPercent();
        int next = Math.max(MIN, Math.min(MAX, from + direction * step.get()));
        setEnabled(true);
        level();
        level = next;
        if (updateToggle.get()) {
            brightness.set(next);
            levelFrom = brightness.get();
        }
        showMessage();
    }

    public int brightnessPercent() {
        return enabled() ? level() : vanillaPercent();
    }

    private int level() {
        if (levelFrom != brightness.get()) {
            levelFrom = brightness.get();
            level = levelFrom;
        }
        return level;
    }

    private static int vanillaPercent() {
        return (int) Math.round(Minecraft.getInstance().options.gamma().get() * 100);
    }

    private void showMessage() {
        Minecraft mc = Minecraft.getInstance();
        if (!hudMessage.get() || mc.player == null) return;
        int gamma = brightnessPercent();
        int color = gamma < 0 ? NEGATIVE_COLOR : gamma > 100 ? POSITIVE_COLOR : DEFAULT_COLOR;
        Compat.actionBar(mc.player, Component.literal("Gamma: " + gamma + "%").withColor(color));
    }

    /** Called from the lightmap mixin with the vanilla gamma option value. */
    public static Object applyGamma(Object original) {
        Fullbright f = instance;
        if (f == null) return original;
        double vanilla = original instanceof Double d ? d : 1.0;
        boolean on = f.enabled();
        double target = on ? f.level() / 100.0 : vanilla;

        if (!f.smooth.get()) {
            f.shown = Double.NaN;
            return on ? target : original;
        }
        long now = System.nanoTime();
        double seconds = f.lastFrameNanos == 0 ? 0 : Math.min(0.25, (now - f.lastFrameNanos) / 1e9);
        f.lastFrameNanos = now;
        if (Double.isNaN(f.shown)) {
            if (!on) return original;
            f.shown = vanilla;
        }
        double maxChange = f.transitionSpeed.get() / 100.0 * seconds;
        double delta = target - f.shown;
        f.shown = Math.abs(delta) <= maxChange ? target : f.shown + Math.signum(delta) * maxChange;
        if (!on && f.shown == target) {
            f.shown = Double.NaN;
            return original;
        }
        return f.shown;
    }
}
