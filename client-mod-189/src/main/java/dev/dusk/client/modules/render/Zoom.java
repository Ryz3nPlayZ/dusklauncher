package dev.dusk.client.modules.render;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.KeySetting;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

/**
 * Zoom, the same module as on 1.21.11 (written from Zoomify's and Ok
 * Zoomer's option lists, not their code). A key narrows the FOV, held or
 * toggled, with eased in and out; the wheel zooms further; the mouse slows
 * with the zoom and view bobbing shrinks with it. A second key gives a slow,
 * far zoom that can hide the HUD. There are no spyglasses on 1.8, so the
 * spyglass options are left out (a copied config keeps them).
 */
public class Zoom extends Module {
    private static final String HOLD = "Hold", TOGGLE = "Toggle";
    private static final String INSTANT = "Instant", LINEAR = "Linear", OUT_SINE = "Ease out (sine)",
            OUT_CUBIC = "Ease out (cubic)", OUT_EXPO = "Ease out (expo)", IN_OUT_SINE = "Ease in-out (sine)",
            IN_OUT_CUBIC = "Ease in-out (cubic)";
    private static final String[] CURVES = {INSTANT, LINEAR, OUT_SINE, OUT_CUBIC, OUT_EXPO, IN_OUT_SINE, IN_OUT_CUBIC};
    private static final String CUSTOM = "Custom", P_DUSK = "Dusk", P_OPTIFINE = "OptiFine-style",
            P_OKZOOMER = "Ok Zoomer-style";
    private static final double MIN_ZOOM = 1.1;

    private static Zoom instance;

    private final KeySetting key = add(new KeySetting("zoom", "Zoom key", Keyboard.KEY_C), "Keybinds");
    private final ChoiceSetting mode = add(new ChoiceSetting("keyMode", "Key mode", HOLD, HOLD, TOGGLE), "Keybinds");
    private final KeySetting inKey = add(new KeySetting("zoom_in", "Zoom further key", Keyboard.KEY_NONE), "Keybinds");
    private final KeySetting outKey = add(new KeySetting("zoom_out", "Zoom back key", Keyboard.KEY_NONE), "Keybinds");
    private final KeySetting secondaryKey = add(new KeySetting("zoom_secondary", "Secondary zoom key", Keyboard.KEY_NONE), "Keybinds");
    private final ChoiceSetting preset = add(new ChoiceSetting("preset", "Preset (applies when picked)", CUSTOM,
            CUSTOM, P_DUSK, P_OPTIFINE, P_OKZOOMER) {
        @Override
        public void set(String value) {
            super.set(value);
            applyPreset(value);
        }
    }, "Zoom");
    private final IntSetting amount = add(new IntSetting("amount", "Zoom", 40, 15, 200, 5, "x").decimals(1), "Zoom");
    private final IntSetting inTime = add(new IntSetting("inTime", "Zoom in time", 30, 0, 200, 5, "s").decimals(2), "Zoom");
    private final ChoiceSetting inCurve = add(new ChoiceSetting("inCurve", "Zoom in curve", OUT_EXPO, CURVES), "Zoom");
    private final IntSetting outTime = add(new IntSetting("outTime", "Zoom out time", 30, 0, 200, 5, "s").decimals(2), "Zoom");
    private final ChoiceSetting outCurve = add(new ChoiceSetting("outCurve", "Zoom out curve", OUT_EXPO, CURVES), "Zoom");
    private final BoolSetting scrollAdjust = add(new BoolSetting("scroll", "Scroll to zoom", true), "Scrolling");
    private final IntSetting step = add(new IntSetting("stepPercent", "Zoom per step", 125, 105, 300, 5, "%"), "Scrolling");
    private final IntSetting maxZoom = add(new IntSetting("maxZoom", "Most zoom", 50, 2, 100, 1, "x"), "Scrolling");
    private final IntSetting scrollTime = add(new IntSetting("scrollTime", "Step smoothing", 15, 0, 100, 5, "s").decimals(2), "Scrolling");
    private final BoolSetting keepScroll = add(new BoolSetting("keepScroll", "Remember scroll zoom", false), "Scrolling");
    private final IntSetting mouse = add(new IntSetting("mouseSlow", "Mouse slowdown", 100, 0, 150, 5, "%"), "Camera");
    private final BoolSetting bobbing = add(new BoolSetting("bobbing", "Shrink view bobbing with zoom", true), "Camera");
    private final BoolSetting cinematic = add(new BoolSetting("cinematic", "Cinematic camera while zoomed", false), "Camera");
    private final IntSetting secondaryAmount = add(new IntSetting("secondaryAmount", "Secondary zoom", 100, 20, 500, 10, "x").decimals(1), "Secondary zoom");
    private final IntSetting secondaryTime = add(new IntSetting("secondaryTime", "Secondary zoom time", 300, 0, 2000, 50, "s").decimals(2), "Secondary zoom");
    private final BoolSetting secondaryHideHud = add(new BoolSetting("secondaryHideHud", "Hide HUD", true), "Secondary zoom");

    private static final int KEY = 0, STEP = 1, SECONDARY = 2;

    private boolean zooming, secondary, wasDown, wasSecondaryDown, wasInDown, wasOutDown;
    private double scrollZoom = 1;
    private double current = 1;
    private double from = 1, to = 1;
    private long startNanos;
    private double duration;
    private String curve = LINEAR;
    private int cause = KEY;
    private boolean setSmoothCamera, setHudHidden;

    public Zoom() {
        super("zoom", "Zoom", Category.RENDER,
                "Hold a key (C by default) to zoom in; scroll while zoomed to zoom further. Eased transitions and a secondary zoom.");
        instance = this;
        setEnabled(true);
    }

    public static Zoom active() {
        Zoom m = instance;
        return m != null && m.enabled() ? m : null;
    }

    @Override
    protected void onDisable() {
        zooming = false;
        secondary = false;
        current = from = to = 1;
        updateOptions(Minecraft.getMinecraft());
    }

    private void applyPreset(String name) {
        if (P_DUSK.equals(name)) {
            amount.set(40); inTime.set(30); inCurve.set(OUT_EXPO); outTime.set(30); outCurve.set(OUT_EXPO);
            scrollAdjust.set(true); step.set(125); scrollTime.set(15); keepScroll.set(false);
            mouse.set(100); bobbing.set(true); cinematic.set(false);
        } else if (P_OPTIFINE.equals(name)) {
            amount.set(40); inTime.set(0); inCurve.set(INSTANT); outTime.set(0); outCurve.set(INSTANT);
            scrollAdjust.set(false); keepScroll.set(false);
            mouse.set(0); bobbing.set(false); cinematic.set(true);
        } else if (P_OKZOOMER.equals(name)) {
            amount.set(40); inTime.set(25); inCurve.set(OUT_EXPO); outTime.set(25); outCurve.set(OUT_EXPO);
            scrollAdjust.set(true); step.set(150); scrollTime.set(20); keepScroll.set(false);
            mouse.set(100); bobbing.set(true); cinematic.set(false);
        }
    }

    /** Polled at the start of every client tick, so a held key sees its release. */
    public void tickKeys(Minecraft mc) {
        boolean playing = mc.thePlayer != null && mc.currentScreen == null;
        boolean down = playing && key.held();
        if (down != wasDown && enabled()) {
            if (mode.is(HOLD)) setZooming(down);
            else if (down) setZooming(!zooming);
        }
        wasDown = down;
        boolean secondaryDown = playing && secondaryKey.held();
        if (secondaryDown && !wasSecondaryDown && enabled()) setSecondary(!secondary);
        wasSecondaryDown = secondaryDown;
        boolean inDown = playing && inKey.held(), outDown = playing && outKey.held();
        if (inDown && !wasInDown) step(1);
        if (outDown && !wasOutDown) step(-1);
        wasInDown = inDown;
        wasOutDown = outDown;
        if ((zooming || secondary) && mc.thePlayer == null) {
            setZooming(false);
            setSecondary(false);
        }
        updateOptions(mc);
    }

    private void setZooming(boolean on) {
        if (on == zooming) return;
        zooming = on;
        cause = KEY;
        if (!on && !keepScroll.get()) scrollZoom = 1;
    }

    private void setSecondary(boolean on) {
        if (on == secondary) return;
        secondary = on;
        cause = SECONDARY;
    }

    private void updateOptions(Minecraft mc) {
        boolean wantSmooth = enabled() && cinematic.get() && (zooming || secondary);
        if (wantSmooth && !mc.gameSettings.smoothCamera) {
            mc.gameSettings.smoothCamera = true;
            setSmoothCamera = true;
        } else if (!wantSmooth && setSmoothCamera) {
            mc.gameSettings.smoothCamera = false;
            setSmoothCamera = false;
        }
        boolean wantHidden = enabled() && secondary && secondaryHideHud.get();
        if (wantHidden && !mc.gameSettings.hideGUI) {
            mc.gameSettings.hideGUI = true;
            setHudHidden = true;
        } else if (!wantHidden && setHudHidden) {
            mc.gameSettings.hideGUI = false;
            setHudHidden = false;
        }
    }

    private double target() {
        if (secondary) return Math.max(MIN_ZOOM, secondaryAmount.get() / 10.0);
        if (!zooming) return 1;
        return clamp(amount.get() / 10.0 * scrollZoom, MIN_ZOOM, Math.max(MIN_ZOOM, maxZoom.get()));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** What the FOV is divided by this frame; 1 when not zoomed. Eased on wall-clock time. */
    public double divisor() {
        long now = System.nanoTime();
        double target = target();
        if (target != to) {
            from = current;
            to = target;
            startNanos = now;
            if (cause == SECONDARY && secondary) {
                duration = secondaryTime.get() / 100.0;
                curve = IN_OUT_SINE;
            } else if (cause == STEP) {
                duration = scrollTime.get() / 100.0;
                curve = OUT_CUBIC;
            } else if (to > from) {
                duration = inTime.get() / 100.0;
                curve = inCurve.get();
            } else {
                duration = outTime.get() / 100.0;
                curve = outCurve.get();
            }
            if (curve.equals(INSTANT)) duration = 0;
        }
        double t = duration <= 0 ? 1 : Math.min(1, (now - startNanos) / 1e9 / duration);
        double e = ease(curve, t);
        current = Math.exp(Math.log(from) + (Math.log(to) - Math.log(from)) * e);
        return current;
    }

    private static double ease(String curve, double t) {
        if (INSTANT.equals(curve)) return 1;
        if (OUT_SINE.equals(curve)) return Math.sin(t * Math.PI / 2);
        if (OUT_CUBIC.equals(curve)) return 1 - Math.pow(1 - t, 3);
        if (OUT_EXPO.equals(curve)) return t >= 1 ? 1 : 1 - Math.pow(2, -10 * t);
        if (IN_OUT_SINE.equals(curve)) return -(Math.cos(Math.PI * t) - 1) / 2;
        if (IN_OUT_CUBIC.equals(curve)) return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
        return t;
    }

    /** Mouse turn scale while zoomed: the setting's share of 1/zoom. */
    public double mouseScale() {
        return current > 1 ? Math.pow(current, -mouse.get() / 100.0) : 1;
    }

    /** View bobbing scale while zoomed. */
    public float bobScale() {
        return bobbing.get() && current > 1 ? (float) (1 / current) : 1;
    }

    /** A wheel notch; true when the zoom used it (so the hotbar doesn't scroll). */
    public boolean scroll(int amount) {
        if (!zooming || secondary || !scrollAdjust.get() || amount == 0) return false;
        step(amount > 0 ? 1 : -1);
        return true;
    }

    private void step(int direction) {
        if (!zooming || secondary) return;
        double base = amount.get() / 10.0, factor = step.get() / 100.0;
        double next = scrollZoom * (direction > 0 ? factor : 1 / factor);
        scrollZoom = clamp(base * next, MIN_ZOOM, Math.max(MIN_ZOOM, maxZoom.get())) / base;
        cause = STEP;
    }

    /** The zoom's FOV divisor for the hook, or 1 when the module is off. */
    public static double fovDivisor() {
        Zoom m = active();
        return m == null ? 1 : m.divisor();
    }

    public static double mouseFactor() {
        Zoom m = active();
        return m == null ? 1 : m.mouseScale();
    }

    public static float bobFactor() {
        Zoom m = active();
        return m == null ? 1 : m.bobScale();
    }
}
