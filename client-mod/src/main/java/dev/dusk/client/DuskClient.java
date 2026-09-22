package dev.dusk.client;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.gui.HudEditorScreen;
import dev.dusk.client.hud.HudHooks;
import dev.dusk.client.module.ModuleManager;
import dev.dusk.client.modules.hud.ArmorStatus;
import dev.dusk.client.modules.hud.Biome;
import dev.dusk.client.modules.hud.Clock;
import dev.dusk.client.modules.hud.ComboDisplay;
import dev.dusk.client.modules.hud.Coordinates;
import dev.dusk.client.modules.hud.CpsCounter;
import dev.dusk.client.modules.hud.DayCounter;
import dev.dusk.client.modules.hud.Direction;
import dev.dusk.client.modules.hud.EntityCount;
import dev.dusk.client.modules.hud.FpsDisplay;
import dev.dusk.client.modules.hud.GameTime;
import dev.dusk.client.modules.hud.HeldItem;
import dev.dusk.client.modules.hud.Keystrokes;
import dev.dusk.client.modules.hud.LightLevel;
import dev.dusk.client.modules.hud.Memory;
import dev.dusk.client.modules.hud.NetherCoordinates;
import dev.dusk.client.modules.hud.Ping;
import dev.dusk.client.modules.hud.Playtime;
import dev.dusk.client.modules.hud.PotionEffects;
import dev.dusk.client.modules.hud.Reach;
import dev.dusk.client.modules.hud.Rotation;
import dev.dusk.client.modules.hud.ServerAddress;
import dev.dusk.client.modules.hud.ShieldStatus;
import dev.dusk.client.modules.hud.Speed;
import dev.dusk.client.modules.hud.SprintStatus;
import dev.dusk.client.modules.hud.Weather;
import dev.dusk.client.modules.render.CustomCrosshair;
import dev.dusk.client.modules.render.Fullbright;
import dev.dusk.client.modules.render.MotionBlur;
import dev.dusk.client.modules.toggle.ToggleSprint;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
        modules.register(new ArmorStatus());
        modules.register(new HeldItem());
        modules.register(new PotionEffects());
        modules.register(new ShieldStatus());
        modules.register(new ComboDisplay());
        modules.register(new Reach());
        modules.register(new SprintStatus());
        // Info HUD
        modules.register(new Coordinates());
        modules.register(new NetherCoordinates());
        modules.register(new Direction());
        modules.register(new Rotation());
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
        // Movement / render
        modules.register(new ToggleSprint());
        modules.register(new CustomCrosshair());
        modules.register(new Fullbright());
        modules.register(new MotionBlur());
        modules.loadConfig();
        DuskConfig.get(); // ensure duskclient.json exists for the launcher bridge
        CosmeticsManager.init();
        HudHooks.register();

        settingsKey = Compat.registerKey("key.duskclient.settings", GLFW.GLFW_KEY_RIGHT_SHIFT);
        KeyMapping fullbrightKey = Compat.registerKey("key.duskclient.fullbright", GLFW.GLFW_KEY_G);
        KeyMapping gammaUpKey = Compat.registerKey("key.duskclient.gamma_up", GLFW.GLFW_KEY_UNKNOWN);
        KeyMapping gammaDownKey = Compat.registerKey("key.duskclient.gamma_down", GLFW.GLFW_KEY_UNKNOWN);
        KeyMapping sprintKey = Compat.registerKey("key.duskclient.togglesprint", GLFW.GLFW_KEY_UNKNOWN);

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
            if (changed) modules.saveConfig();
            modules.tick();
        });
        LOGGER.info("DuskClient initialized with {} modules", modules.all().size());
    }
}
