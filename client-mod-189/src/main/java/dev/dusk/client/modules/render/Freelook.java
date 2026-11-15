package dev.dusk.client.modules.render;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.KeySetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Keyboard;

/**
 * Freelook: while the key is held the camera swings behind you and the mouse
 * orbits it all the way round, while the player keeps facing where it was;
 * letting go puts the view back. 1.8.9 port: EntityRendererMixin feeds
 * {@link #turn} from the mouse and reads {@link #yaw}/{@link #pitch} in
 * orientCamera; RenderManagerMixin and ActiveRenderInfoMixin turn nametags
 * and particles to face the camera.
 */
public class Freelook extends Module {
    private static final String HOLD = "Hold", TOGGLE = "Toggle";
    private static final String BACK = "Third person", FRONT = "Front view", KEEP = "Current view";

    private static Freelook instance;

    private final KeySetting key = add(new KeySetting("freelook", "Freelook key", Keyboard.KEY_LMENU), "Keybinds");
    private final ChoiceSetting mode = add(new ChoiceSetting("keyMode", "Key mode", HOLD, HOLD, TOGGLE), "Keybinds");
    private final ChoiceSetting view = add(new ChoiceSetting("view", "View while looking", BACK, BACK, FRONT, KEEP), "Camera");
    private final BoolSetting invertPitch = add(new BoolSetting("invertPitch", "Invert up/down", false), "Camera");

    private boolean looking, wasDown;
    private float yaw, pitch;
    /** thirdPersonView before we changed it, and the one we set (-1: left alone). */
    private int previousView = -1, ourView = -1;

    public Freelook() {
        super("freelook", "Freelook", Category.RENDER,
                "Hold a key (Left Alt by default) to look around without turning your player.");
        instance = this;
        setEnabled(true);
    }

    /** The module while it is steering the camera (and freecam isn't), else null. */
    public static Freelook looking() {
        Freelook m = instance;
        return m != null && m.enabled() && m.looking && Freecam.active() == null ? m : null;
    }

    /** True when {@code k} is bound to the same key as freelook's, which then wins. */
    public static boolean usesKey(KeyBinding k) {
        Freelook m = instance;
        return m != null && m.enabled() && k.getKeyCode() != 0 && k.getKeyCode() == m.key.binding().getKeyCode();
    }

    @Override
    protected void onDisable() {
        stop();
    }

    public float yaw() { return yaw; }

    public float pitch() { return pitch; }

    /* ── keys (polled every client tick, so a held key sees its release) ── */

    public void tickKeys(Minecraft mc) {
        // the physical key, so a hold survives vanilla's key state; never while typing in a screen
        boolean down = mc.currentScreen == null && key.held();
        if (down != wasDown && enabled()) {
            if (mode.is(HOLD)) { if (down) start(mc); else stop(); }
            else if (down) { if (looking) stop(); else start(mc); }
        }
        wasDown = down;
        if (looking && (mc.thePlayer == null || Freecam.active() != null)) stop();
    }

    private void start(Minecraft mc) {
        if (looking || mc.thePlayer == null || Freecam.active() != null) return;
        looking = true;
        yaw = mc.thePlayer.rotationYaw;
        pitch = mc.thePlayer.rotationPitch;
        previousView = mc.gameSettings.thirdPersonView;
        ourView = view.is(BACK) ? 1 : view.is(FRONT) ? 2 : -1;
        if (ourView != -1) mc.gameSettings.thirdPersonView = ourView;
    }

    private void stop() {
        if (!looking) return;
        looking = false;
        Minecraft mc = Minecraft.getMinecraft();
        // F5 during freelook wins: only undo the view we set ourselves
        if (ourView != -1 && previousView != -1 && mc.gameSettings.thirdPersonView == ourView) {
            mc.gameSettings.thirdPersonView = previousView;
        }
        ourView = previousView = -1;
    }

    /** Mouse movement while looking, in the units {@code EntityPlayerSP.setAngles} takes. */
    public void turn(float dYaw, float dPitch) {
        yaw += dYaw * 0.15F;
        pitch = MathHelper.clamp_float(pitch - (invertPitch.get() ? -dPitch : dPitch) * 0.15F, -90F, 90F);
    }
}
