package dev.dusk.client.modules.render;

import dev.dusk.client.compat.Input;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.KeySetting;
import dev.dusk.client.render.shield.ShieldStateTracker;
import dev.dusk.client.render.shield.ShieldTint;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Map;

/**
 * Everything about the shield in one place. Shield height and the riptide fix
 * follow BactroMod (behaviour only); hiding the idle shield is
 * ShieldDisruptor's (MIT, see NOTICE); showing other players' blocks properly
 * is Shield Fixes' remote use sync and the status tints are Shield Statuses
 * (both Walksy, MIT, see NOTICE). Replaces Low Shield, Riptide Shield Fix and
 * Shield Statuses. Shield Fixes' block and break sounds are left out: from
 * 1.21.11 the server plays them, so they would play twice.
 */
public class ShieldTweaks extends Module {
    private static ShieldTweaks instance;

    private final IntSetting offset = add(new IntSetting("offset", "Shield height", 0, -100, 100, 1, "%"));
    private final BoolSetting hideIdle = add(new BoolSetting("hideIdle", "Hide off-hand shield when not blocking", false));
    private final BoolSetting riptide = add(new BoolSetting("riptide", "Keep shield in place while riptiding", true));
    private final BoolSetting syncOthers = add(new BoolSetting("syncOthers", "Show others' blocks without delay", true));

    private final BoolSetting tint = add(new BoolSetting("tint", "Tint shields by status", false), "Status colours");
    private final BoolSetting colorInterpolation = add(new BoolSetting("colorInterpolation", "Fade colour over the cooldown", false), "Status colours");
    private final BoolSetting selfStateOnly = add(new BoolSetting("selfStateOnly", "Only tint your own shield", false), "Status colours");
    private final KeySetting toggleSelfState = add(new KeySetting("shield_self_state", "Toggle own-shield-only key", Input.UNKNOWN), "Status colours");
    private final BoolSetting customEnabled = add(new BoolSetting("customEnabledShieldColor", "Tint ready shields", true), "Status colours");
    private final ColorSetting enabledColor = add(new ColorSetting("enabledColor", "Ready colour", 0xFF00FF00), "Status colours");
    private final BoolSetting customUsing = add(new BoolSetting("customUsingShieldColor", "Tint raised shields", false), "Status colours");
    private final ColorSetting usingColor = add(new ColorSetting("usingColor", "Raised colour", 0xFF00FF00), "Status colours");
    private final BoolSetting customRising = add(new BoolSetting("customRisingShieldColor", "Tint shields being raised", false), "Status colours");
    private final ColorSetting risingColor = add(new ColorSetting("risingColor", "Raising colour", 0xFFFFFF00), "Status colours");
    private final BoolSetting customDisabled = add(new BoolSetting("customDisabledShieldColor", "Tint disabled shields", true), "Status colours");
    private final ColorSetting disabledColor = add(new ColorSetting("disabledColor", "Disabled colour", 0xFFFF0000), "Status colours");

    public ShieldTweaks() {
        super("shieldtweaks", "Shield Tweaks", Category.RENDER,
                "Lower or hide your shield, keep it steady while riptiding, see others block on time and tint shields by status.");
        instance = this;
        for (var s : List.of(colorInterpolation, selfStateOnly, toggleSelfState, customEnabled, customUsing,
                customRising, customDisabled)) {
            s.visibleWhen(tint::get);
        }
        enabledColor.visibleWhen(() -> tint.get() && customEnabled.get());
        usingColor.visibleWhen(() -> tint.get() && customUsing.get());
        risingColor.visibleWhen(() -> tint.get() && customRising.get());
        disabledColor.visibleWhen(() -> tint.get() && customDisabled.get());
        setEnabled(true);
    }

    @Override
    public List<String> formerIds() {
        return List.of("lowshield", "riptideshieldfix", "shieldstatuses");
    }

    /** Each old module that was on turns its part on; one that was off leaves its part at the default. */
    @Override
    public boolean loadFormerState(String formerId, Map<String, Object> state) {
        boolean wasOn = Boolean.TRUE.equals(state.get("enabled"));
        switch (formerId) {
            case "lowshield" -> {
                if (wasOn) loadState(state);
            }
            case "riptideshieldfix" -> riptide.set(wasOn);
            case "shieldstatuses" -> {
                loadState(state);
                tint.set(wasOn);
            }
            default -> {}
        }
        return true;
    }

    private static boolean on() {
        return instance != null && instance.enabled();
    }

    /** True while the shield tracker should follow who is blocking and who is disabled. */
    public static boolean tracking() {
        return on() && (instance.tint.get() || instance.syncOthers.get());
    }

    @Override
    protected void onDisable() {
        ShieldStateTracker.reset();
    }

    @Override
    public void tick() {
        if (!tracking()) return;
        ShieldStateTracker.tick();
        if (syncOthers.get()) ShieldStateTracker.syncRemoteUse();
    }

    // --- first-person shield ---

    public static float offsetY() {
        return on() ? instance.offset.get() / 100f : 0;
    }

    public static boolean riptideFix() {
        return on() && instance.riptide.get();
    }

    /** ShieldDisruptor: skip drawing the off-hand shield while you aren't using an item. */
    public static boolean hideIdle(InteractionHand hand) {
        if (!on() || !instance.hideIdle.get() || hand != InteractionHand.OFF_HAND) return false;
        Player player = Minecraft.getInstance().player;
        return player != null && !player.isUsingItem();
    }

    // --- status tints ---

    public static boolean tintActive() {
        return on() && instance.tint.get();
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

    /** The tint for {@code player}'s shield, {@link ShieldTint#NONE} for none. Config.getColor in Shield Statuses. */
    public static int colorFor(Player player) {
        if (!tintActive() || player == null) return ShieldTint.NONE;
        ShieldTweaks s = instance;
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
