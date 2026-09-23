package dev.dusk.client;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.gui.HudEditorScreen;
import dev.dusk.client.hud.HudHooks;
import dev.dusk.client.hud.Raycast;
import dev.dusk.client.hud.TpsTracker;
import dev.dusk.client.module.ModuleManager;
import dev.dusk.client.modules.hud.ArmorStatus;
import dev.dusk.client.modules.hud.Biome;
import dev.dusk.client.modules.hud.Clock;
import dev.dusk.client.modules.hud.ComboDisplay;
import dev.dusk.client.modules.hud.Compass;
import dev.dusk.client.modules.hud.Coordinates;
import dev.dusk.client.modules.hud.CpsCounter;
import dev.dusk.client.modules.hud.DayCounter;
import dev.dusk.client.modules.hud.Direction;
import dev.dusk.client.modules.hud.Distance;
import dev.dusk.client.modules.hud.EntityCount;
import dev.dusk.client.modules.hud.FpsDisplay;
import dev.dusk.client.modules.hud.FullInventory;
import dev.dusk.client.modules.hud.GameTime;
import dev.dusk.client.modules.hud.HeldItem;
import dev.dusk.client.modules.hud.InventoryDisplay;
import dev.dusk.client.modules.hud.Keystrokes;
import dev.dusk.client.modules.hud.LightLevel;
import dev.dusk.client.modules.hud.Memory;
import dev.dusk.client.modules.hud.NetherCoordinates;
import dev.dusk.client.modules.hud.Ping;
import dev.dusk.client.modules.hud.PitchDisplay;
import dev.dusk.client.modules.hud.Playtime;
import dev.dusk.client.modules.hud.PotionEffects;
import dev.dusk.client.modules.hud.Reach;
import dev.dusk.client.modules.hud.Rotation;
import dev.dusk.client.modules.hud.ServerAddress;
import dev.dusk.client.modules.hud.ShieldStatus;
import dev.dusk.client.modules.hud.SignReader;
import dev.dusk.client.modules.hud.SneakStatus;
import dev.dusk.client.modules.hud.Speed;
import dev.dusk.client.modules.hud.SprintStatus;
import dev.dusk.client.modules.hud.Tps;
import dev.dusk.client.modules.hud.Weather;
import dev.dusk.client.modules.misc.BoatMap;
import dev.dusk.client.modules.misc.GameModeSwitcher;
import dev.dusk.client.modules.misc.TntCountdown;
import dev.dusk.client.modules.render.CustomCrosshair;
import dev.dusk.client.modules.render.ColorSaturation;
import dev.dusk.client.modules.render.DamageTint;
import dev.dusk.client.modules.render.FogControl;
import dev.dusk.client.modules.render.Fullbright;
import dev.dusk.client.modules.render.Hitbox;
import dev.dusk.client.modules.render.ItemScale;
import dev.dusk.client.modules.render.LowFire;
import dev.dusk.client.modules.render.LowShield;
import dev.dusk.client.modules.render.MotionBlur;
import dev.dusk.client.modules.render.NoNightVision;
import dev.dusk.client.modules.render.NoPumpkinBlur;
import dev.dusk.client.modules.render.RiptideShieldFix;
import dev.dusk.client.modules.render.TimeChanger;
import dev.dusk.client.modules.render.WeatherChanger;
import dev.dusk.client.modules.toggle.ToggleSprint;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Registers every module and the keybinds. Anti-cheat safety
 * rule: modules are client-side-only (rendering/HUD/input) and never alter
 * outbound packets or movement math.
 */
public class DuskClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("duskclient");
    private static ModuleManager modules;
    private static KeyMapping settingsKey;

    public static ModuleManager modules() {
        return modules;
    }

    /** The key that opens (and, inside the editor, closes) the HUD editor. */
    public static KeyMapping settingsKey() {
        return settingsKey;
    }

    @Override
    public void onInitializeClient() {
        modules = new ModuleManager();
        // Keystroke-ish HUD
        modules.register(new Keystrokes());
        modules.register(new CpsCounter());
        modules.register(new FpsDisplay());
        modules.register(new Ping());
        modules.register(new Tps());
        modules.register(new ArmorStatus());
        modules.register(new HeldItem());
        modules.register(new PotionEffects());
        modules.register(new ShieldStatus());
        modules.register(new ComboDisplay());
        modules.register(new Reach());
        modules.register(new SprintStatus());
        modules.register(new SneakStatus());
        modules.register(new FullInventory());
        modules.register(new InventoryDisplay());
        // Info HUD
        modules.register(new Coordinates());
        modules.register(new NetherCoordinates());
        modules.register(new Direction());
        modules.register(new Compass());
        modules.register(new Rotation());
        modules.register(new PitchDisplay());
        modules.register(new Speed());
        modules.register(new Biome());
        modules.register(new LightLevel());
        modules.register(new Clock());
        modules.register(new GameTime());
        modules.register(new DayCounter());
        modules.register(new Weather());
        modules.register(new Playtime());
        modules.register(new EntityCount());
        modules.register(new Memory());
        modules.register(new ServerAddress());
        modules.register(new Distance());
        modules.register(new SignReader());
        // Movement / render
        modules.register(new ToggleSprint());
        modules.register(new CustomCrosshair());
        modules.register(new Fullbright());
        modules.register(new MotionBlur());
        modules.register(new TntCountdown());
        if (Compat.MODERN_CLIENT_HOOKS) {
            // BactroMod ports; they hook client internals only 1.21.11+ has
            modules.register(new NoPumpkinBlur());
            modules.register(new LowFire());
            modules.register(new LowShield());
            modules.register(new NoNightVision());
            modules.register(new RiptideShieldFix());
            modules.register(new ItemScale());
            modules.register(new FogControl());
            modules.register(new BoatMap());
            modules.register(new GameModeSwitcher());
            modules.register(new TimeChanger());
            modules.register(new WeatherChanger());
            modules.register(new DamageTint());
            modules.register(new ColorSaturation());
            modules.register(new Hitbox());
        }
        modules.loadConfig();
        DuskConfig.get(); // ensure duskclient.json exists for the launcher bridge
        CosmeticsManager.init();
        HudHooks.register();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> TpsTracker.reset());

        settingsKey = Compat.registerKey("key.duskclient.settings", GLFW.GLFW_KEY_RIGHT_SHIFT);
        KeyMapping fullbrightKey = Compat.registerKey("key.duskclient.fullbright", GLFW.GLFW_KEY_G);
        KeyMapping gammaUpKey = Compat.registerKey("key.duskclient.gamma_up", GLFW.GLFW_KEY_UNKNOWN);
        KeyMapping gammaDownKey = Compat.registerKey("key.duskclient.gamma_down", GLFW.GLFW_KEY_UNKNOWN);
        KeyMapping sprintKey = Compat.registerKey("key.duskclient.togglesprint", GLFW.GLFW_KEY_UNKNOWN);
        // PolyTime's own defaults; they step the Time Changer slider by an hour.
        KeyMapping timeForwardKey = Compat.registerKey("key.duskclient.time_forward", GLFW.GLFW_KEY_RIGHT_BRACKET);
        KeyMapping timeBackwardKey = Compat.registerKey("key.duskclient.time_backward", GLFW.GLFW_KEY_LEFT_BRACKET);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (settingsKey.consumeClick()) {
                if (client.player != null && !(Compat.currentScreen(client) instanceof HudEditorScreen)) {
                    Compat.setScreen(client, new HudEditorScreen(Compat.currentScreen(client)));
                }
            }
            boolean changed = false;
            while (fullbrightKey.consumeClick()) {
                Fullbright f = Fullbright.instance();
                f.setEnabled(!f.enabled());
                changed = true;
            }
            while (gammaUpKey.consumeClick()) {
                Fullbright.instance().adjust(1);
                changed = true;
            }
            while (gammaDownKey.consumeClick()) {
                Fullbright.instance().adjust(-1);
                changed = true;
            }
            while (sprintKey.consumeClick()) {
                ToggleSprint t = modules.get(ToggleSprint.class);
                t.setEnabled(!t.enabled());
                changed = true;
            }
            while (timeForwardKey.consumeClick()) {
                if (TimeChanger.instance() != null) {
                    TimeChanger.instance().shift(1);
                    changed = true;
                }
            }
            while (timeBackwardKey.consumeClick()) {
                if (TimeChanger.instance() != null) {
                    TimeChanger.instance().shift(-1);
                    changed = true;
                }
            }
            if (changed) modules.saveConfig();
            if (modules.get(Distance.class).enabled() || modules.get(SignReader.class).enabled()) {
                Raycast.tick(client);
            }
            modules.tick();
        });
        LOGGER.info("DuskClient initialized with {} modules", modules.all().size());
    }
}
