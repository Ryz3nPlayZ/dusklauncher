package dev.dusk.client.modules.toggle;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.hud.Keys;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.KeySetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import org.lwjgl.glfw.GLFW;

import java.util.function.Function;

/**
 * Toggle Toggle Sprint (Zlib, celestialfault — see NOTICE): keys that flip
 * vanilla's own Sprint and Sneak "Toggle" controls, and press the vanilla key
 * for you. Sprinting itself stays the game's, so a server sees exactly what it
 * would from a player who set Sprint to Toggle in Controls.
 *
 * <p>Toggle Sneak has no key by default here: the original's Right Shift opens
 * the Dusk menu.
 */
public class ToggleSprint extends Module {
    private static final String UNCHANGED = "Don't modify", ON = "Toggle", OFF = "Hold";
    private static final String NEVER = "Never", ALWAYS = "Always", WHEN_UNTOGGLED = "When enabling";

    private static ToggleSprint instance;

    private final Control sprint = new Control("sprint", "Sprint", GLFW.GLFW_KEY_RIGHT_CONTROL, true, ALWAYS,
            Options::toggleSprint, o -> o.keySprint);
    private final Control sneak = new Control("sneak", "Sneak", GLFW.GLFW_KEY_UNKNOWN, false, WHEN_UNTOGGLED,
            Options::toggleCrouch, o -> o.keyShift);
    private final BoolSetting keepSprintingOnDeath = add(new BoolSetting("keepSprintingOnDeath", "Keep sprinting after death", true));

    private boolean inWorld;

    public ToggleSprint() {
        super("togglesprint", "Toggle Sprint", Category.MOVEMENT,
                "Keys for vanilla's Toggle Sprint and Toggle Sneak, so sprinting stays the game's own.");
        instance = this;
        setEnabled(true);
    }

    /** Whether respawning should leave the Sprint toggle latched. */
    public static boolean keepsSprintOnDeath() {
        ToggleSprint m = instance;
        return m != null && m.enabled() && m.keepSprintingOnDeath.get();
    }

    /**
     * The two keys, every client tick. A press while the module is off is
     * dropped rather than saved up for when it comes back on. Never changes
     * the module config, so always false.
     */
    public boolean tickKeys() {
        Minecraft mc = Minecraft.getInstance();
        if (enabled()) firstWorldTick(mc);
        sprint.tick(mc, enabled());
        sneak.tick(mc, enabled());
        return false;
    }

    private void firstWorldTick(Minecraft mc) {
        // key states are left alone while any screen is open
        if (Compat.currentScreen(mc) != null) return;
        if (mc.level == null && inWorld) {
            inWorld = false;
        } else if (mc.level != null && !inWorld) {
            sprint.firstWorldTick(mc);
            sneak.firstWorldTick(mc);
            inWorld = true;
        }
    }

    /** One toggling key: Toggle Sprint or Toggle Sneak, and its options. */
    private final class Control {
        private final KeySetting key;
        private final BoolSetting onJoin;
        private final ChoiceSetting defaultState;
        private final ChoiceSetting activateKey;
        private final Function<Options, OptionInstance<Boolean>> toggle;
        private final Function<Options, KeyMapping> vanillaKey;

        Control(String id, String name, int defaultKey, boolean onJoinDefault, String activationDefault,
                Function<Options, OptionInstance<Boolean>> toggle, Function<Options, KeyMapping> vanillaKey) {
            String verb = id.equals("sprint") ? "sprinting" : "sneaking";
            this.key = add(new KeySetting("toggle_" + id, "Toggle " + name + " key", defaultKey), name);
            this.onJoin = add(new BoolSetting(id + "OnJoin", "Start " + verb, onJoinDefault), name);
            this.defaultState = add(new ChoiceSetting(id + "DefaultState", "Default " + id + " state",
                    UNCHANGED, UNCHANGED, ON, OFF), name);
            this.activateKey = add(new ChoiceSetting(id + "Activation", "Simulate " + name + " key press",
                    activationDefault, NEVER, ALWAYS, WHEN_UNTOGGLED), name);
            this.toggle = toggle;
            this.vanillaKey = vanillaKey;
        }

        void firstWorldTick(Minecraft mc) {
            OptionInstance<Boolean> toggle = this.toggle.apply(mc.options);
            KeyMapping key = vanillaKey.apply(mc.options);
            if (defaultState.is(ON)) {
                toggle.set(true);
            } else if (defaultState.is(OFF)) {
                toggle.set(false);
                // a latched key can linger after the toggle goes off, until it is pressed again
                key.setDown(false);
            }
            if (onJoin.get() && toggle.get() && !key.isDown()) key.setDown(true);
        }

        void tick(Minecraft mc, boolean active) {
            while (key.mapping().consumeClick()) {
                if (active) onPress(mc);
            }
        }

        private void onPress(Minecraft mc) {
            OptionInstance<Boolean> toggle = this.toggle.apply(mc.options);
            KeyMapping key = vanillaKey.apply(mc.options);
            if (activateKey.is(ALWAYS) && toggle.get() && !key.isDown()) {
                key.setDown(true);
                return;
            }
            toggle.set(!toggle.get());
            if (!toggle.get()) {
                key.setDown(Keys.physicallyDown(key));
            } else if (!activateKey.is(NEVER) && !key.isDown()) {
                key.setDown(true);
            }
        }
    }
}
