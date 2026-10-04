package dev.dusk.client.modules.render;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.KeySetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * FreeLook (freelook-og, MIT, jmilthedude — see NOTICE): hold Left Alt, or
 * flip it on with Right Alt, and the mouse turns your head while your body
 * keeps facing where it was, up to 100° either way; letting go eases the view
 * back. With Third person on (the default, as Lunar's and Celibistrial's
 * freelook do it) the view also pulls back behind you while you look and
 * snaps back to first person when you let go. Off in front view. Not registered when a standalone freelook mod is
 * installed. The camera mixin calls {@link #aim} as the camera is aimed and
 * reads {@link #yaw}/{@link #pitch}; the mouse mixin feeds {@link #turn}.
 */
public class Freelook extends Module {
    private static final float SHOULDER_LIMIT = 100f;

    private static Freelook instance;

    private final KeySetting useKey = add(new KeySetting("freelook", "Freelook key", GLFW.GLFW_KEY_LEFT_ALT), "Keybinds");
    private final KeySetting toggleKey = add(new KeySetting("freelook_toggle", "Toggle key", GLFW.GLFW_KEY_RIGHT_ALT), "Keybinds");
    private final BoolSetting thirdPerson = add(new BoolSetting("thirdPerson", "Third person", true), "Camera");
    private final BoolSetting clampView = add(new BoolSetting("clampView", "Clamp to shoulders", true), "Camera");
    private final BoolSetting interpolate = add(new BoolSetting("interpolate", "Smooth return", true), "Camera");
    private final IntSetting interpolateTime = add(new IntSetting("interpolateSpeed", "Return time", 200, 50, 1000, 50, "ms"), "Camera");

    private boolean toggled;
    /** Non-null from the first aimed frame until the view is back where it started. */
    private State state;
    private boolean interpolating;
    /** The view to go back to, while freelook has pulled the camera into third person. */
    private CameraType restoreCamera;

    public Freelook() {
        super("freelook", "Freelook", Category.RENDER,
                "Hold Left Alt to look around you in third person without turning.");
        instance = this;
        setEnabled(true);
    }

    /** The module while it is steering the camera, else null. */
    public static Freelook looking() {
        Freelook m = instance;
        if (m == null || !m.enabled() || m.state == null) return null;
        return Minecraft.getInstance().options.getCameraType().isMirrored() ? null : m;
    }

    /**
     * Called every frame as the camera is aimed, before it reads the view
     * direction; returns {@link #looking()}.
     */
    public static Freelook aim() {
        Freelook m = instance;
        if (m != null) m.update();
        return looking();
    }

    /** True when {@code key} is bound to the same key as freelook's, which then wins. */
    public static boolean usesKey(KeyMapping key) {
        Freelook m = instance;
        return m != null && m.enabled() && !key.isUnbound()
                && (key.same(m.useKey.mapping()) || key.same(m.toggleKey.mapping()));
    }

    public float yaw() { return state.yaw; }

    public float pitch() { return state.pitch; }

    /** The toggle key, every client tick. */
    public void tickKeys() {
        LocalPlayer player = Minecraft.getInstance().player;
        while (toggleKey.mapping().consumeClick()) {
            if (player == null || !enabled()) continue;
            toggled = !toggled;
            Compat.actionBar(player, Component.literal("FreeLook Toggle: " + toggled));
        }
    }

    @Override
    protected void onDisable() {
        toggled = false;
        deactivate();
    }

    private void update() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !enabled()) {
            deactivate();
            return;
        }
        if (mc.options.getCameraType().isMirrored()) return;
        if (useKey.mapping().isDown() || toggled) {
            interpolating = false;
            if (state == null) {
                state = new State(player.getYRot(), player.getXRot());
                if (thirdPerson.get() && mc.options.getCameraType() == CameraType.FIRST_PERSON) {
                    restoreCamera = CameraType.FIRST_PERSON;
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                }
                return;
            }
            lockPlayerRotation(player);
        } else if (interpolating) {
            lockPlayerRotation(player);
            if (state.interpolate(interpolateTime.get())) deactivate();
        } else if (state != null) {
            // back in first person the view just snaps home, nothing to ease
            if (interpolate.get() && restoreCamera == null) {
                state.startInterpolation();
                interpolating = true;
            } else {
                deactivate();
            }
        }
    }

    /** Mouse movement while looking, in the units {@code Entity.turn} takes. Ignored while easing back. */
    public void turn(double dx, double dy) {
        if (state == null || interpolating) return;
        state.yaw += (float) dx * 0.15F;
        // behind you there are no shoulders to stop at: the camera goes all the way round
        if (clampView.get() && restoreCamera == null) {
            state.yaw = Mth.clamp(state.yaw, state.originalYaw - SHOULDER_LIMIT, state.originalYaw + SHOULDER_LIMIT);
        }
        state.pitch = Mth.clamp(state.pitch + (float) dy * 0.15F, -90f, 90f);
    }

    /**
     * The body keeps its heading; in first person the head and the aim follow
     * the view, in third person the player stays exactly as it was.
     */
    private void lockPlayerRotation(LocalPlayer player) {
        boolean still = restoreCamera != null;
        player.setYRot(state.originalYaw);
        player.yBodyRot = state.originalYaw;
        player.yHeadRot = still ? state.originalYaw : state.yaw;
        player.setXRot(still ? state.originalPitch : state.pitch);
    }

    private void deactivate() {
        state = null;
        interpolating = false;
        if (restoreCamera != null) {
            Minecraft mc = Minecraft.getInstance();
            // only undo our own switch, not an F5 pressed meanwhile
            if (mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK) mc.options.setCameraType(restoreCamera);
            restoreCamera = null;
        }
    }

    private static final class State {
        final float originalYaw, originalPitch;
        float yaw, pitch;
        private float lerpStartYaw, lerpStartPitch;
        private long lerpStartTime;

        State(float yaw, float pitch) {
            this.originalYaw = this.yaw = yaw;
            this.originalPitch = this.pitch = pitch;
        }

        void startInterpolation() {
            lerpStartYaw = yaw;
            lerpStartPitch = pitch;
            lerpStartTime = System.currentTimeMillis();
        }

        /** Eases the view back to where it started; true once it is there. */
        boolean interpolate(int durationMs) {
            float t = Math.min((float) (System.currentTimeMillis() - lerpStartTime) / durationMs, 1f);
            yaw = Mth.lerp(t, lerpStartYaw, originalYaw);
            pitch = Mth.lerp(t, lerpStartPitch, originalPitch);
            return t >= 1f;
        }
    }
}
