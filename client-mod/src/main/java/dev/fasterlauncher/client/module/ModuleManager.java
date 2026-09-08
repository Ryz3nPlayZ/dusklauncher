package dev.fasterlauncher.client.module;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registry + config persistence for modules. */
public class ModuleManager {
    private static final Gson GSON = new Gson();
    private final List<Module> modules = new ArrayList<>();

    public void register(Module module) {
        modules.add(module);
    }

    public List<Module> all() {
        return modules;
    }

    public Module byId(String id) {
        return modules.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }

    public void tick() {
        for (Module m : modules) {
            if (m.enabled()) m.tick();
        }
    }

    private Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("fasterclient.json");
    }

    public void loadConfig() {
        try {
            Path path = configFile();
            if (!Files.exists(path)) return;
            Type type = new TypeToken<Map<String, Map<String, Object>>>() {}.getType();
            Map<String, Map<String, Object>> data = GSON.fromJson(Files.readString(path), type);
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
