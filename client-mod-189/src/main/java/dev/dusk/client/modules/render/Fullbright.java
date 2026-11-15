package dev.dusk.client.modules.render;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.KeySetting;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

/**
 * Gamma Utils' gamma controls (behaviour only; that mod is LGPL — see
 * NOTICE): G flips between the normal gamma and a toggled one (1500%), the
 * arrow keys step it by 10% between -750% and 1500%, and each change shows
 * "Gamma: N%" on the action bar. Only the lightmap reads it.
 */
public class Fullbright extends Module {
    private static final int MIN = -750, MAX = 1500;

    private static Fullbright instance;

    private final KeySetting toggleKey = add(new KeySetting("fullbright", "Toggle key", Keyboard.KEY_G));
    private final KeySetting upKey = add(new KeySetting("gamma_up", "Increase key", Keyboard.KEY_UP));
    private final KeySetting downKey = add(new KeySetting("gamma_down", "Decrease key", Keyboard.KEY_DOWN));
    private final IntSetting brightness = add(new IntSetting("brightness", "Toggled gamma", 1500, MIN, MAX, 10, "%"));
    private final IntSetting step = add(new IntSetting("step", "Gamma step", 10, 10, 500, 10, "%"));
    private final BoolSetting updateToggle = add(new BoolSetting("updateToggle", "Remember adjusted gamma", false));
    private final BoolSetting hudMessage = add(new BoolSetting("hudMessage", "Show gamma message", true));
    private final BoolSetting smooth = add(new BoolSetting("smoothTransition", "Smooth transition", false));
    private final IntSetting transitionSpeed = add(new IntSetting("transitionSpeed", "Transition speed", 3000, 100, 10000, 100, "%/s"));

    private int level;
    private int levelFrom = Integer.MIN_VALUE;
    private double shown = Double.NaN;
    private long lastFrameNanos;

    public Fullbright() {
        super("fullbright", "Fullbright", Category.RENDER, "Night vision without the potion: boosts gamma beyond 100%.");
        instance = this;
    }

    /** Handles the three keys; true when a setting changed. */
    public boolean tickKeys() {
        boolean changed = false;
        while (toggleKey.binding().isPressed()) {
            setEnabled(!enabled());
            showMessage();
            changed = true;
        }
        while (upKey.binding().isPressed()) {
            adjust(1);
            changed = true;
        }
        while (downKey.binding().isPressed()) {
            adjust(-1);
            changed = true;
        }
        return changed;
    }

    @Override
    protected void onEnable() { levelFrom = Integer.MIN_VALUE; }

    private void adjust(int direction) {
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

    private int brightnessPercent() { return enabled() ? level() : vanillaPercent(); }

    private int level() {
        if (levelFrom != brightness.get()) {
            levelFrom = brightness.get();
            level = levelFrom;
        }
        return level;
    }

    private static int vanillaPercent() {
        return Math.round(Minecraft.getMinecraft().gameSettings.gammaSetting * 100);
    }

    private void showMessage() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!hudMessage.get() || mc.thePlayer == null) return;
        int gamma = brightnessPercent();
        EnumChatFormatting color = gamma < 0 ? EnumChatFormatting.DARK_RED
                : gamma > 100 ? EnumChatFormatting.GOLD : EnumChatFormatting.DARK_GREEN;
        mc.ingameGUI.setRecordPlaying(new ChatComponentText("Gamma: " + gamma + "%")
                .setChatStyle(new ChatStyle().setColor(color)).getFormattedText(), false);
    }

    /** Whether the lightmap update in progress read the gamma through the vanilla hook. */
    private static boolean gammaRead;

    /**
     * Just before the lightmap goes to the GPU. With a resource pack that has
     * its own lightmap (mcpatcher/lightmap), OptiFine's Custom Colors fills the
     * colours itself and reads the gamma option capped at 100%, so the vanilla
     * hook never runs. Then the boost is laid over the finished colours with
     * the same curve vanilla uses.
     */
    public static void beforeLightmapUpload(int[] colors) {
        boolean read = gammaRead;
        gammaRead = false;
        Fullbright f = instance;
        if (read || colors == null || f == null || (!f.enabled() && Double.isNaN(f.shown))) return;
        float vanilla = Minecraft.getMinecraft().gameSettings.gammaSetting;
        float extra = gamma(vanilla) - Math.min(1f, vanilla);
        gammaRead = false;
        if (extra == 0f) return;
        for (int i = 0; i < colors.length; i++) {
            int c = colors[i];
            colors[i] = (c & 0xFF000000) | boost(c >> 16 & 0xFF, extra) << 16
                    | boost(c >> 8 & 0xFF, extra) << 8 | boost(c & 0xFF, extra);
        }
    }

    /** Vanilla's gamma blend for one channel: toward 1 - (1 - v)^4, lifted off black like vanilla. */
    private static int boost(int channel, float extra) {
        float v = channel / 255f;
        float dark = 1f - (v * 0.96f + 0.03f);
        float curve = 1f - dark * dark * dark * dark;
        float out = v + extra * (curve - v);
        return Math.round(Math.max(0f, Math.min(1f, out)) * 255f);
    }

    /** The gamma the lightmap should use, given the options slider's. */
    public static float gamma(float vanilla) {
        gammaRead = true;
        Fullbright f = instance;
        if (f == null) return vanilla;
        boolean on = f.enabled();
        double target = on ? f.level() / 100.0 : vanilla;
        if (!f.smooth.get()) {
            f.shown = Double.NaN;
            return (float) target;
        }
        long now = System.nanoTime();
        double seconds = f.lastFrameNanos == 0 ? 0 : Math.min(0.25, (now - f.lastFrameNanos) / 1e9);
        f.lastFrameNanos = now;
        if (Double.isNaN(f.shown)) {
            if (!on) return vanilla;
            f.shown = vanilla;
        }
        double maxChange = f.transitionSpeed.get() / 100.0 * seconds;
        double delta = target - f.shown;
        f.shown = Math.abs(delta) <= maxChange ? target : f.shown + Math.signum(delta) * maxChange;
        if (!on && f.shown == target) {
            f.shown = Double.NaN;
            return vanilla;
        }
        return (float) f.shown;
    }
}
