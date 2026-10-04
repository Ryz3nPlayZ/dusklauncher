package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.KeySetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * Freelook: while the key is held the camera swings behind you and the mouse
 * orbits it all the way round, while the player keeps facing where it was;
 * letting go puts the view back. Not registered when a standalone freelook
 * mod is installed. The mouse mixin feeds {@link #turn}; the camera mixin
 * calls {@link #aim} and reads {@link #yaw} and {@link #pitch}.
 */
public class Freelook extends Module {
    private static final String HOLD = "Hold", TOGGLE = "Toggle";
    private static final String BACK = "Third person", FRONT = "Front view", KEEP = "Current view";

    private static Freelook instance;

    private final KeySetting key = add(new KeySetting("freelook", "Freelook key", GLFW.GLFW_KEY_LEFT_ALT), "Keybinds");
    private final ChoiceSetting mode = add(new ChoiceSetting("keyMode", "Key mode", HOLD, HOLD, TOGGLE), "Keybinds");
    private final ChoiceSetting view = add(new ChoiceSetting("view", "View while looking", BACK, BACK, FRONT, KEEP), "Camera");
    private final BoolSetting invertPitch = add(new BoolSetting("invertPitch", "Invert up/down", false), "Camera");

    private boolean looking, wasDown;
    private float yaw, pitch;
    private CameraType previousView, ourView;

    public Freelook() {
        super("freelook", "Freelook", Category.RENDER,
                "Hold a key (Left Alt by default) to look around without turning your player.");
        instance = this;
        setEnabled(true);
    }

    /** The module while it is steering the camera, else null. */
    public static Freelook looking() {
        Freelook m = instance;
        return m != null && m.enabled() && m.looking ? m : null;
    }

    /** Called as the camera is aimed, before it reads the view direction; same as {@link #looking()}. */
    public static Freelook aim() {
        return looking();
    }

    /** True when {@code key} is bound to the same key as freelook's, which then wins. */
    public static boolean usesKey(KeyMapping key) {
        Freelook m = instance;
        return m != null && m.enabled() && !key.isUnbound() && key.same(m.key.mapping());
    }

    @Override
    protected void onDisable() {
        stop();
    }

    public float yaw() { return yaw; }

    public float pitch() { return pitch; }

    /* ── keys (polled every client tick, so a held key sees its release) ── */

    public void tickKeys() {
        Minecraft mc = Minecraft.getInstance();
        boolean down = key.mapping().isDown();
        if (down != wasDown && enabled()) {
            if (mode.is(HOLD)) { if (down) start(); else stop(); }
            else if (down) { if (looking) stop(); else start(); }
        }
        wasDown = down;
        if (looking && mc.player == null) stop();
    }

    private void start() {
        Minecraft mc = Minecraft.getInstance();
        if (looking || mc.player == null) return;
        looking = true;
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
        previousView = mc.options.getCameraType();
        ourView = view.is(BACK) ? CameraType.THIRD_PERSON_BACK : view.is(FRONT) ? CameraType.THIRD_PERSON_FRONT : null;
        if (ourView != null) mc.options.setCameraType(ourView);
    }

    private void stop() {
        if (!looking) return;
        looking = false;
        Minecraft mc = Minecraft.getInstance();
        // F5 during freelook wins: only undo the view we set ourselves
        if (ourView != null && previousView != null && mc.options.getCameraType() == ourView) {
            mc.options.setCameraType(previousView);
        }
        ourView = previousView = null;
    }

    /** Mouse movement while looking, in the same units {@code Entity.turn} takes. */
    public void turn(double dx, double dy) {
        yaw += (float) dx * 0.15F;
        pitch = Mth.clamp(pitch + (float) (invertPitch.get() ? -dy : dy) * 0.15F, -90F, 90F);
    }
}
