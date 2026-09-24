package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Port of Polyfrost's BehindYouV3 (SnapLook): keys that flip the camera to
 * the back or front view and back, with an eased zoom-out and an FOV of its
 * own for each third-person view. The camera mixins read {@link #level},
 * {@link #fov} and the {@link CameraType} overrides below.
 */
public class BehindYou extends Module {
    private static final String HOLD = "Hold", TOGGLE = "Toggle";
    private static final String OFF = "Off", ENTER = "Enter", RETURN = "Return", BOTH = "Both";

    // blocks from the target at which the eased zoom counts as arrived for the first-person switch
    private static final float ARRIVAL_EPSILON = 0.5F;
    private static final float VANILLA_DISTANCE = 4F;

    private static BehindYou instance;

    private final ChoiceSetting backMode = add(new ChoiceSetting("backKeyMode", "Back view key mode", HOLD, HOLD, TOGGLE), "Keybinds");
    private final ChoiceSetting frontMode = add(new ChoiceSetting("frontKeyMode", "Front view key mode", HOLD, HOLD, TOGGLE), "Keybinds");
    private final BoolSetting enableF5 = add(new BoolSetting("enableF5", "Animate the F5 key", false), "Keybinds");

    private final BoolSetting animate = add(new BoolSetting("animation", "Animation", true), "Animation");
    private final IntSetting speed = add(new IntSetting("animationSpeed", "Animation time", 4, 1, 20, 1, "s").decimals(1), "Animation");
    private final ChoiceSetting backAnim = add(new ChoiceSetting("backAnimation", "Back view animation", BOTH, OFF, ENTER, RETURN, BOTH), "Animation");
    private final ChoiceSetting frontAnim = add(new ChoiceSetting("frontAnimation", "Front view animation", BOTH, OFF, ENTER, RETURN, BOTH), "Animation");

    private final BoolSetting fovEnabled = add(new BoolSetting("fov", "Change FOV", true), "FOV");
    private final IntSetting backFov = add(new IntSetting("backFov", "Back view FOV", 90, 30, 110, 1, ""), "FOV");
    private final IntSetting frontFov = add(new IntSetting("frontFov", "Front view FOV", 90, 30, 110, 1, ""), "FOV");

    private final IntSetting backDistance = add(new IntSetting("backDistance", "Back view distance", 40, 10, 40, 1, "").decimals(1), "Distance");
    private final IntSetting frontDistance = add(new IntSetting("frontDistance", "Front view distance", 40, 10, 40, 1, "").decimals(1), "Distance");

    private final Animation zAnimation = new Animation();
    private final Animation fovAnimation = new Animation();
    private boolean animationsReady;
    private CameraType previousPerspective = CameraType.FIRST_PERSON;
    private CameraType managedPerspective;

    private boolean backWasDown, frontWasDown;
    private int seenBackFov, seenFrontFov, seenBackDistance, seenFrontDistance;

    public BehindYou() {
        super("behindyou", "Behind You", Category.RENDER,
                "Keys that flip the camera behind or in front of you, with a smooth zoom and their own FOV.");
        instance = this;
    }

    public static BehindYou active() {
        BehindYou m = instance;
        return m != null && m.enabled() ? m : null;
    }

    @Override
    protected void onDisable() {
        managedPerspective = null;
    }

    private static Minecraft mc() { return Minecraft.getInstance(); }

    private static float vanillaFov() { return mc().options.fov().get().floatValue(); }

    /* ── keys (polled every client tick, so a held key sees its release) ── */

    public void tickKeys(KeyMapping backKey, KeyMapping frontKey) {
        boolean back = backKey.isDown(), front = frontKey.isDown();
        if (back != backWasDown) onKey(back, backMode, CameraType.THIRD_PERSON_BACK);
        if (front != frontWasDown) onKey(front, frontMode, CameraType.THIRD_PERSON_FRONT);
        backWasDown = back;
        frontWasDown = front;
        syncChangedSettings();
    }

    private void onKey(boolean down, ChoiceSetting mode, CameraType view) {
        if (!enabled() || mc().player == null) return;
        if (mode.is(TOGGLE) && !down) return;
        CameraType target;
        if (mode.is(HOLD) && !down) target = CameraType.FIRST_PERSON;
        else if (mc().options.getCameraType() == view) target = CameraType.FIRST_PERSON;
        else target = view;
        updatePerspective(target);
    }

    /** BehindYou reacts to its sliders live: the active view snaps to the new distance or FOV. */
    private void syncChangedSettings() {
        if (seenBackFov != backFov.get() || seenBackDistance != backDistance.get())
            syncActivePerspective(CameraType.THIRD_PERSON_BACK);
        if (seenFrontFov != frontFov.get() || seenFrontDistance != frontDistance.get())
            syncActivePerspective(CameraType.THIRD_PERSON_FRONT);
        seenBackFov = backFov.get();
        seenFrontFov = frontFov.get();
        seenBackDistance = backDistance.get();
        seenFrontDistance = frontDistance.get();
    }

    /* ── camera state ── */

    public boolean capturesF5() { return enabled() && enableF5.get(); }

    public boolean animationEnabled() { return animate.get(); }

    public CameraType previousPerspective() { return previousPerspective; }

    public boolean isManagingPerspective() {
        CameraType managed = managedPerspective;
        if (managed == null) return false;
        if (mc().options.getCameraType() != managed) {
            managedPerspective = null;
            return false;
        }
        if (managed == CameraType.FIRST_PERSON && isFinished()
                && (!fovEnabled.get() || fovAnimation.finished)) {
            managedPerspective = null;
            return false;
        }
        return true;
    }

    public boolean isFinished() {
        setupAnimations();
        if (zAnimation.finished) return true;
        return Math.abs(zAnimation.value - zAnimation.to) <= ARRIVAL_EPSILON;
    }

    /** The third-person camera distance, eased toward the managed view's target. */
    public float level(float zIn) {
        if (!isManagingPerspective()) return zIn;
        setupAnimations();
        return Math.min(zAnimation.update(), zIn);
    }

    public float fov(float fovIn) {
        if (!fovEnabled.get() || !isManagingPerspective()) return fovIn;
        setupAnimations();
        if (mc().options.getCameraType() == CameraType.FIRST_PERSON
                && fovAnimation.finished && fovAnimation.to != fovIn) {
            fovAnimation.from = fovIn;
            fovAnimation.to = fovIn;
            fovAnimation.finishNow();
        }
        return fovAnimation.update();
    }

    public void updatePerspective(CameraType perspective) {
        CameraType current = mc().options.getCameraType();
        if (current == perspective) return;
        if (!isManagingPerspective()) resetAnimationsToVanilla(current);

        float z = 0.3F, targetFov = vanillaFov();
        if (isThirdPerson(perspective)) {
            z = distance(perspective);
            targetFov = viewFov(perspective);
        }
        boolean animated = shouldAnimate(current, perspective);

        setTargetLevel(z, targetFov, animated);
        if (animated && current != CameraType.FIRST_PERSON && perspective != CameraType.FIRST_PERSON) {
            zAnimation.from = 0.3F;
            zAnimation.reset(durationNanos());
        }

        previousPerspective = current;
        managedPerspective = perspective;
        mc().options.setCameraType(perspective);
    }

    private boolean shouldAnimate(CameraType from, CameraType to) {
        if (!animate.get()) return false;
        boolean returning = to == CameraType.FIRST_PERSON;
        CameraType view = returning ? from : to;
        ChoiceSetting mode;
        if (view == CameraType.THIRD_PERSON_FRONT) mode = frontAnim;
        else if (view == CameraType.THIRD_PERSON_BACK) mode = backAnim;
        else return false;
        return returning ? mode.is(RETURN) || mode.is(BOTH) : mode.is(ENTER) || mode.is(BOTH);
    }

    private void resetAnimationsToVanilla(CameraType perspective) {
        setupAnimations();
        zAnimation.to = perspective == CameraType.FIRST_PERSON ? 0F : VANILLA_DISTANCE;
        zAnimation.finishNow();
        fovAnimation.to = vanillaFov();
        fovAnimation.finishNow();
    }

    private void setTargetLevel(float z, float targetFov, boolean animated) {
        setupAnimations();
        zAnimation.to = z;
        if (animated) {
            zAnimation.from = zAnimation.value;
            zAnimation.reset(durationNanos());
        } else {
            zAnimation.finishNow();
        }

        if (!fovEnabled.get()) return;
        fovAnimation.to = targetFov;
        if (animated) {
            fovAnimation.from = fovAnimation.value;
            fovAnimation.reset(durationNanos());
        } else {
            fovAnimation.finishNow();
        }
    }

    private void setupAnimations() {
        if (animationsReady) return;
        animationsReady = true;
        CameraType type = mc().options.getCameraType();
        float z = isThirdPerson(type) ? distance(type) : 0F;
        float f = isThirdPerson(type) ? viewFov(type) : vanillaFov();
        zAnimation.from = zAnimation.to = z;
        zAnimation.finishNow();
        fovAnimation.from = fovAnimation.to = f;
        fovAnimation.finishNow();
    }

    private void syncActivePerspective(CameraType perspective) {
        if (!isManagingPerspective() || mc().options.getCameraType() != perspective) return;
        setupAnimations();
        zAnimation.to = distance(perspective);
        zAnimation.finishNow();
        fovAnimation.to = viewFov(perspective);
        fovAnimation.finishNow();
    }

    private static boolean isThirdPerson(CameraType type) {
        return type == CameraType.THIRD_PERSON_BACK || type == CameraType.THIRD_PERSON_FRONT;
    }

    private float distance(CameraType view) {
        return (view == CameraType.THIRD_PERSON_FRONT ? frontDistance : backDistance).get() / 10F;
    }

    private float viewFov(CameraType view) {
        return (view == CameraType.THIRD_PERSON_FRONT ? frontFov : backFov).get();
    }

    private long durationNanos() { return speed.get() * 100_000_000L; }

    /** easeOutQuart over wall-clock time, so the length never depends on framerate. */
    private static final class Animation {
        float from, to, value;
        boolean finished = true;
        private long startNanos, durationNanos = 1;

        float update() {
            if (finished) return value;
            double t = Math.clamp((System.nanoTime() - startNanos) / (double) durationNanos, 0.0, 1.0);
            double inv = 1.0 - t;
            value = (float) (1.0 - inv * inv * inv * inv) * (to - from) + from;
            if (t >= 1.0) finished = true;
            return value;
        }

        void reset(long duration) {
            durationNanos = Math.max(1, duration);
            startNanos = System.nanoTime();
            finished = false;
            value = from;
        }

        void finishNow() {
            finished = true;
            value = to;
        }
    }
}
