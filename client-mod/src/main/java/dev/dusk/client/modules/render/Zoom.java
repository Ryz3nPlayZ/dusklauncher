package dev.dusk.client.modules.render;

import dev.dusk.client.compat.HudToggle;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.KeySetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.Set;

/**
 * Zoom with the feature set of Zoomify and Ok Zoomer, written from their
 * option lists rather than their code (both are behaviour references only,
 * see NOTICE). A key narrows the FOV, held or toggled. Zooming in and out
 * each have their own time and easing curve. The wheel, or a pair of
 * optional keys, steps the zoom further. The mouse slows by a chosen share
 * of the zoom, and view bobbing shrinks with it. Cinematic camera can come
 * on while zoomed. A spyglass can be required, with its overlay and sounds.
 * A second key gives a slow, far "secondary" zoom that can hide the HUD.
 * Not registered when Zoomify itself is installed. The FOV, mouse and
 * bobbing mixins read {@link #divisor}, {@link #mouseScale},
 * {@link #bobScale} and {@link #scroll}.
 */
public class Zoom extends Module {
    private static final String HOLD = "Hold", TOGGLE = "Toggle";
    private static final String INSTANT = "Instant", LINEAR = "Linear", OUT_SINE = "Ease out (sine)",
            OUT_CUBIC = "Ease out (cubic)", OUT_EXPO = "Ease out (expo)", IN_OUT_SINE = "Ease in-out (sine)",
            IN_OUT_CUBIC = "Ease in-out (cubic)";
    private static final String[] CURVES = {INSTANT, LINEAR, OUT_SINE, OUT_CUBIC, OUT_EXPO, IN_OUT_SINE, IN_OUT_CUBIC};
    private static final String ANY = "Any time", HOLDING = "Holding a spyglass", CARRYING = "Carrying a spyglass";
    private static final String NEVER = "Never", ALWAYS = "Always";
    private static final String CUSTOM = "Custom", P_DUSK = "Dusk", P_OPTIFINE = "OptiFine-style",
            P_OKZOOMER = "Ok Zoomer-style", P_SPYGLASS = "Spyglass";
    private static final double MIN_ZOOM = 1.1;
    private static final String SCOPE = "minecraft:textures/misc/spyglass_scope.png";
    private static final Set<net.minecraft.world.item.Item> SPYGLASS = Set.of(Items.SPYGLASS);

    private static Zoom instance;

    private final KeySetting key = add(new KeySetting("zoom", "Zoom key", GLFW.GLFW_KEY_C), "Keybinds");
    private final ChoiceSetting mode = add(new ChoiceSetting("keyMode", "Key mode", HOLD, HOLD, TOGGLE), "Keybinds");
    private final KeySetting inKey = add(new KeySetting("zoom_in", "Zoom further key", GLFW.GLFW_KEY_UNKNOWN), "Keybinds");
    private final KeySetting outKey = add(new KeySetting("zoom_out", "Zoom back key", GLFW.GLFW_KEY_UNKNOWN), "Keybinds");
    private final KeySetting secondaryKey = add(new KeySetting("zoom_secondary", "Secondary zoom key", GLFW.GLFW_KEY_UNKNOWN), "Keybinds");

    private final ChoiceSetting preset = add(new ChoiceSetting("preset", "Preset (applies when picked)", CUSTOM,
            CUSTOM, P_DUSK, P_OPTIFINE, P_OKZOOMER, P_SPYGLASS) {
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

    private final ChoiceSetting spyglass = add(new ChoiceSetting("spyglass", "Zoom works", ANY, ANY, HOLDING, CARRYING), "Spyglass");
    private final ChoiceSetting overlay = add(new ChoiceSetting("overlay", "Spyglass overlay", NEVER, NEVER, HOLDING, ALWAYS), "Spyglass");
    private final BoolSetting sounds = add(new BoolSetting("sounds", "Spyglass sounds", false), "Spyglass");

    private final IntSetting secondaryAmount = add(new IntSetting("secondaryAmount", "Secondary zoom", 100, 20, 500, 10, "x").decimals(1), "Secondary zoom");
    private final IntSetting secondaryTime = add(new IntSetting("secondaryTime", "Secondary zoom time", 300, 0, 2000, 50, "s").decimals(2), "Secondary zoom");
    private final BoolSetting secondaryHideHud = add(new BoolSetting("secondaryHideHud", "Hide HUD", true), "Secondary zoom");

    private enum Cause { KEY, STEP, SECONDARY }

    private boolean zooming, secondary, wasDown, wasSecondaryDown;
    // extra zoom from the wheel and step keys, multiplied onto the setting
    private double scrollZoom = 1;
    private double current = 1;
    // the running transition, in log space: from → to, started at startNanos
    private double from = 1, to = 1;
    private long startNanos;
    private double duration;
    private String curve = LINEAR;
    private Cause cause = Cause.KEY;
    // what we switched on ourselves, so only that is switched back
    private boolean setSmoothCamera, setHudHidden;

    public Zoom() {
        super("zoom", "Zoom", Category.RENDER,
                "Hold a key (C by default) to zoom in; scroll while zoomed to zoom further. Eased transitions, spyglass mode and a secondary zoom.");
        instance = this;
        setEnabled(true);
    }

    public static Zoom active() {
        Zoom m = instance;
        return m != null && m.enabled() ? m : null;
    }

    @Override
    protected void onDisable() {
        setZooming(false);
        setSecondary(false);
        current = from = to = 1;
        restoreOptions();
    }

    /* ── presets ── */

    private void applyPreset(String name) {
        switch (name) {
            case P_DUSK -> {
                amount.set(40); inTime.set(30); inCurve.set(OUT_EXPO); outTime.set(30); outCurve.set(OUT_EXPO);
                scrollAdjust.set(true); step.set(125); scrollTime.set(15); keepScroll.set(false);
                mouse.set(100); bobbing.set(true); cinematic.set(false);
                spyglass.set(ANY); overlay.set(NEVER); sounds.set(false);
            }
            case P_OPTIFINE -> {
                amount.set(40); inTime.set(0); inCurve.set(INSTANT); outTime.set(0); outCurve.set(INSTANT);
                scrollAdjust.set(false); keepScroll.set(false);
                mouse.set(0); bobbing.set(false); cinematic.set(true);
                spyglass.set(ANY); overlay.set(NEVER); sounds.set(false);
            }
            case P_OKZOOMER -> {
                amount.set(40); inTime.set(25); inCurve.set(OUT_EXPO); outTime.set(25); outCurve.set(OUT_EXPO);
                scrollAdjust.set(true); step.set(150); scrollTime.set(20); keepScroll.set(false);
                mouse.set(100); bobbing.set(true); cinematic.set(false);
                spyglass.set(ANY); overlay.set(NEVER); sounds.set(false);
            }
            case P_SPYGLASS -> {
                amount.set(100); inTime.set(25); inCurve.set(OUT_CUBIC); outTime.set(15); outCurve.set(OUT_CUBIC);
                scrollAdjust.set(true); step.set(125); scrollTime.set(15); keepScroll.set(false);
                mouse.set(100); bobbing.set(true); cinematic.set(false);
                spyglass.set(HOLDING); overlay.set(HOLDING); sounds.set(true);
            }
            default -> {}
        }
    }

    /* ── keys (polled every client tick, so a held key sees its release) ── */

    public void tickKeys() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        boolean down = key.mapping().isDown();
        if (down != wasDown && enabled()) {
            if (mode.is(HOLD)) setZooming(down);
            else if (down) setZooming(!zooming);
        }
        wasDown = down;

        boolean secondaryDown = secondaryKey.mapping().isDown();
        if (secondaryDown && !wasSecondaryDown && enabled()) setSecondary(!secondary);
        wasSecondaryDown = secondaryDown;

        while (inKey.mapping().consumeClick()) step(1);
        while (outKey.mapping().consumeClick()) step(-1);

        // a menu, death or a put-away spyglass drops the zoom
        if ((zooming || secondary) && (player == null || !allowed(player))) {
            setZooming(false);
            setSecondary(false);
        }
        updateOptions(mc);
    }

    private boolean allowed(LocalPlayer player) {
        if (spyglass.is(HOLDING)) return holdingSpyglass(player);
        if (spyglass.is(CARRYING)) return player.getInventory().hasAnyOf(SPYGLASS);
        return true;
    }

    private static boolean holdingSpyglass(LocalPlayer player) {
        return player.getMainHandItem().is(Items.SPYGLASS) || player.getOffhandItem().is(Items.SPYGLASS);
    }

    private void setZooming(boolean on) {
        if (on == zooming) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (on && (player == null || !allowed(player))) return;
        zooming = on;
        cause = Cause.KEY;
        if (!on && !keepScroll.get()) scrollZoom = 1;
        if (!secondary) playSound(on);
    }

    private void setSecondary(boolean on) {
        if (on == secondary) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (on && (player == null || !allowed(player))) return;
        secondary = on;
        cause = Cause.SECONDARY;
        if (!zooming) playSound(on);
    }

    private void playSound(boolean in) {
        if (!sounds.get() || Minecraft.getInstance().player == null) return;
        SoundEvent sound = in ? SoundEvents.SPYGLASS_USE : SoundEvents.SPYGLASS_STOP_USING;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0f, 1.0f));
    }

    /** Cinematic camera and the hidden HUD follow the zoom, and go back to what they were after. */
    private void updateOptions(Minecraft mc) {
        boolean wantSmooth = enabled() && cinematic.get() && (zooming || secondary);
        if (wantSmooth && !mc.options.smoothCamera) {
            mc.options.smoothCamera = true;
            setSmoothCamera = true;
        } else if (!wantSmooth && setSmoothCamera) {
            mc.options.smoothCamera = false;
            setSmoothCamera = false;
        }
        boolean wantHidden = enabled() && secondary && secondaryHideHud.get();
        if (wantHidden && !HudToggle.hidden(mc)) {
            HudToggle.setHidden(mc, true);
            setHudHidden = true;
        } else if (!wantHidden && setHudHidden) {
            if (HudToggle.hidden(mc)) HudToggle.setHidden(mc, false);
            setHudHidden = false;
        }
    }

    private void restoreOptions() {
        updateOptions(Minecraft.getInstance());
    }

    /* ── the zoom itself ── */

    private double target() {
        if (secondary) return Math.max(MIN_ZOOM, secondaryAmount.get() / 10.0);
        if (!zooming) return 1;
        return Math.clamp(amount.get() / 10.0 * scrollZoom, MIN_ZOOM, Math.max(MIN_ZOOM, maxZoom.get()));
    }

    /** What the FOV is divided by this frame; 1 when not zoomed. Eased on wall-clock time. */
    public double divisor() {
        long now = System.nanoTime();
        double target = target();
        if (target != to) {
            from = current;
            to = target;
            startNanos = now;
            if (cause == Cause.SECONDARY && secondary) {
                duration = secondaryTime.get() / 100.0;
                curve = IN_OUT_SINE;
            } else if (cause == Cause.STEP) {
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
        // ease in log space so 2x→4x takes as long as 4x→8x
        double e = ease(curve, t);
        current = Math.exp(Math.log(from) + (Math.log(to) - Math.log(from)) * e);
        return current;
    }

    private static double ease(String curve, double t) {
        return switch (curve) {
            case INSTANT -> 1;
            case OUT_SINE -> Math.sin(t * Math.PI / 2);
            case OUT_CUBIC -> 1 - Math.pow(1 - t, 3);
            case OUT_EXPO -> t >= 1 ? 1 : 1 - Math.pow(2, -10 * t);
            case IN_OUT_SINE -> -(Math.cos(Math.PI * t) - 1) / 2;
            case IN_OUT_CUBIC -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
            default -> t;
        };
    }

    /** How far into the zoom the view is, 0 (none) to 1 (fully zoomed). */
    private double progress() {
        double full = Math.max(target(), to);
        if (full <= 1 || current <= 1) return 0;
        return Math.min(1, Math.log(current) / Math.log(full));
    }

    /** Mouse turn scale while zoomed: the setting's share of 1/zoom, so a flick covers the same screen distance. */
    public double mouseScale() {
        return current > 1 ? Math.pow(current, -mouse.get() / 100.0) : 1;
    }

    /** View bobbing scale while zoomed. */
    public float bobScale() {
        return bobbing.get() && current > 1 ? (float) (1 / current) : 1;
    }

    /** A wheel notch while zoomed; returns true when the zoom consumed it. */
    public boolean scroll(double amount) {
        if (!zooming || secondary || !scrollAdjust.get() || amount == 0) return false;
        step(amount > 0 ? 1 : -1);
        return true;
    }

    private void step(int direction) {
        if (!zooming || secondary) return;
        double base = amount.get() / 10.0, factor = step.get() / 100.0;
        double next = scrollZoom * (direction > 0 ? factor : 1 / factor);
        scrollZoom = Math.clamp(base * next, MIN_ZOOM, Math.max(MIN_ZOOM, maxZoom.get())) / base;
        cause = Cause.STEP;
    }

    /* ── spyglass overlay (drawn under the HUD) ── */

    public static void drawOverlay(Canvas c, HudContext ctx) {
        Zoom m = active();
        if (m == null || m.overlay.is(NEVER) || ctx.player() == null) return;
        if (m.overlay.is(HOLDING) && !holdingSpyglass(ctx.player())) return;
        double p = m.progress();
        if (p <= 0) return;
        int w = ctx.width(), h = ctx.height();
        // the scope grows into place as the zoom comes in
        int size = (int) (Math.min(w, h) * (0.5 + 0.625 * p));
        int x = (w - size) / 2, y = (h - size) / 2;
        c.push();
        c.translate(x, y);
        c.scale(size / 256f, size / 256f);
        c.blit(SCOPE, 0, 0, 0, 0, 256, 256, 256, 256);
        c.pop();
        int black = 0xFF000000;
        c.fill(0, 0, w, Math.max(0, y), black);
        c.fill(0, Math.min(h, y + size), w, h, black);
        c.fill(0, Math.max(0, y), Math.max(0, x), Math.min(h, y + size), black);
        c.fill(Math.min(w, x + size), Math.max(0, y), w, Math.min(h, y + size), black);
    }
}
