package dev.dusk.client.module;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.hud.HudElement;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry + config persistence for modules. State lives in
 * config/duskclient-hud.json; config/duskclient.json belongs to
 * {@link dev.dusk.client.config.DuskConfig} and the launcher bridge.
 */
public class ModuleManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final List<Module> modules = new ArrayList<>();
    private final List<HudElement> hudElements = new ArrayList<>();
    /** Modules whose toggle key was down last tick, so holding it flips once. */
    private final Set<Module> toggleHeld = new HashSet<>();

    public void register(Module module) {
        modules.add(module);
        if (module instanceof HudElement e) hudElements.add(e);
    }

    public List<Module> all() {
        return modules;
    }

    public List<HudElement> hudElements() {
        return hudElements;
    }

    public Module byId(String id) {
        return modules.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    public <M extends Module> M get(Class<M> type) {
        for (Module m : modules) if (type.isInstance(m)) return (M) m;
        return null;
    }

    /**
     * Flips modules whose toggle key was just pressed, in game with no
     * screen open. True when one changed, so the caller saves.
     */
    public boolean tickToggleKeys(Minecraft mc) {
        long window = GLFW.glfwGetCurrentContext();
        boolean inGame = window != 0L && mc.player != null && Compat.currentScreen(mc) == null;
        boolean changed = false;
        for (Module m : modules) {
            boolean down = inGame && m.toggleKey().bound()
                    && GLFW.glfwGetKey(window, m.toggleKey().get()) == GLFW.GLFW_PRESS;
            if (!down) {
                toggleHeld.remove(m);
                continue;
            }
            if (!toggleHeld.add(m)) continue;
            if (m.blocked()) {
                Compat.actionBar(mc.player, Component.literal(m.name() + " is disabled by this server"));
                continue;
            }
            m.setEnabled(!m.enabled());
            changed = true;
            Compat.actionBar(mc.player, Component.literal(m.name() + (m.enabled() ? " on" : " off")));
        }
        return changed;
    }

    public void tick() {
        for (Module m : modules) {
            if (m.enabled()) m.tick();
        }
    }

    private Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("duskclient-hud.json");
    }

    public void loadConfig() {
        try {
            Path path = configFile();
            if (!Files.exists(path)) return;
            Type type = new TypeToken<Map<String, Map<String, Object>>>() {}.getType();
            Map<String, Map<String, Object>> data = GSON.fromJson(Files.readString(path), type);
            if (data == null) return;
            for (Module m : modules) {
                Map<String, Object> state = data.get(m.id());
                if (state != null) m.loadState(state);
            }
        } catch (Exception e) {
            // corrupt or incompatible config: start fresh rather than crash
        }
    }

    public void saveConfig() {
        try {
            Map<String, Map<String, Object>> data = new LinkedHashMap<>();
            for (Module m : modules) data.put(m.id(), m.saveState());
            Files.createDirectories(configFile().getParent());
            Files.writeString(configFile(), GSON.toJson(data));
        } catch (Exception e) {
            // best-effort persistence
        }
    }
}
