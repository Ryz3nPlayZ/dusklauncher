package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.KeySetting;
import dev.dusk.client.render.shield.ShieldStateTracker;
import dev.dusk.client.render.shield.ShieldTint;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

/**
 * A port of Walksy's Shield Statuses (MIT, see NOTICE): shields are tinted by
 * their holder's state, green while they can block and red while an axe has
 * disabled them, for your own shield and everyone else's. Options and
 * defaults follow its Config; the grayscale and custom-texture options are
 * not carried over.
 */
public class ShieldStatuses extends Module {
    private static ShieldStatuses instance;

    private final BoolSetting colorInterpolation = add(new BoolSetting("colorInterpolation", "Fade colour over the cooldown", false));
    private final BoolSetting selfStateOnly = add(new BoolSetting("selfStateOnly", "Only tint your own shield", false));
    private final KeySetting toggleSelfState = add(new KeySetting("shield_self_state", "Toggle own-shield-only key", GLFW.GLFW_KEY_UNKNOWN));
    private final BoolSetting customEnabled = add(new BoolSetting("customEnabledShieldColor", "Tint ready shields", true), "Colours");
    private final ColorSetting enabledColor = add(new ColorSetting("enabledColor", "Ready colour", 0xFF00FF00), "Colours");
    private final BoolSetting customUsing = add(new BoolSetting("customUsingShieldColor", "Tint raised shields", false), "Colours");
    private final ColorSetting usingColor = add(new ColorSetting("usingColor", "Raised colour", 0xFF00FF00), "Colours");
    private final BoolSetting customRising = add(new BoolSetting("customRisingShieldColor", "Tint shields being raised", false), "Colours");
    private final ColorSetting risingColor = add(new ColorSetting("risingColor", "Raising colour", 0xFFFFFF00), "Colours");
    private final BoolSetting customDisabled = add(new BoolSetting("customDisabledShieldColor", "Tint disabled shields", true), "Colours");
    private final ColorSetting disabledColor = add(new ColorSetting("disabledColor", "Disabled colour", 0xFFFF0000), "Colours");

    public ShieldStatuses() {
        super("shieldstatuses", "Shield Statuses", Category.RENDER,
                "Tints shields green when they can block and red while disabled, yours and other players'.");
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.enabled();
    }

    @Override
    protected void onDisable() {
        ShieldStateTracker.reset();
    }

    @Override
    public void tick() {
        ShieldStateTracker.tick();
    }

    public static boolean tickKeys() {
        if (instance == null) return false;
        boolean changed = false;
        while (instance.toggleSelfState.mapping().consumeClick()) {
            instance.selfStateOnly.toggle();
            changed = true;
        }
        return changed;
    }

    /** The tint for {@code player}'s shield, {@link ShieldTint#NONE} for none. Config.getColor in the original. */
    public static int colorFor(Player player) {
        if (!active() || player == null) return ShieldTint.NONE;
        ShieldStatuses s = instance;
        if (player != Minecraft.getInstance().player && s.selfStateOnly.get()) return ShieldTint.NONE;
        boolean cd = ShieldStateTracker.isCoolingDown(player);
        boolean active = ShieldStateTracker.isUsingShield(player);
        boolean rising = ShieldStateTracker.isHoldingUsableShield(player) && player.isUsingItem() && !active && !cd;
        if (rising && s.customRising.get()) return s.risingColor.argb();
        if (active && s.customUsing.get()) return s.usingColor.argb();
        int enabled = s.customEnabled.get() ? s.enabledColor.argb() : ShieldTint.NONE;
        int disabled = s.customDisabled.get() ? s.disabledColor.argb() : ShieldTint.NONE;
        if (!s.colorInterpolation.get()) return cd ? disabled : enabled;
        float progress = cd ? ShieldStateTracker.getCooldownProgress(player) : 0f;
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = enabled >>> shift & 0xFF, b = disabled >>> shift & 0xFF;
            out |= ((int) (a + (b - a) * progress) & 0xFF) << shift;
        }
        return out;
    }
}
