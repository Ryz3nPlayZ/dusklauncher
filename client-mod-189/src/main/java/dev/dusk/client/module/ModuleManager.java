package dev.dusk.client.module;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.util.Guard;
import dev.dusk.client.util.Json;
import dev.dusk.client.util.Keys;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * All modules, their toggle keys, and config/duskclient-hud.json. Modules
 * this build doesn't have keep their saved entries, so copying the file back
 * to a modern instance loses nothing.
 */
public final class ModuleManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final List<Module> modules = new ArrayList<Module>();
    private final List<HudElement> hudElements = new ArrayList<HudElement>();
    private final Set<Module> toggleHeld = new HashSet<Module>();
    private final Map<String, Object> unknown = new LinkedHashMap<String, Object>();
    private boolean dirty;

    public <M extends Module> M register(M m) {
        modules.add(m);
        if (m instanceof HudElement) hudElements.add((HudElement) m);
        return m;
    }

    public List<Module> modules() { return Collections.unmodifiableList(modules); }
    public List<Module> all() { return modules(); }
    public List<HudElement> hudElements() { return Collections.unmodifiableList(hudElements); }

    public Module byId(String id) {
        for (Module m : modules) if (m.id().equals(id)) return m;
        return null;
    }

    @SuppressWarnings("unchecked")
    public <M extends Module> M get(Class<M> type) {
        for (Module m : modules) if (type.isInstance(m)) return (M) m;
        return null;
    }

    /** Toggle keys, in game with no screen open: holding one flips its module once. */
    public void tickToggleKeys(Minecraft mc) {
        boolean inGame = mc.thePlayer != null && mc.currentScreen == null;
        for (Module m : modules) {
            boolean down = inGame && m.toggleKey().bound() && Keys.isDown(m.toggleKey().get());
            if (!down) {
                toggleHeld.remove(m);
                continue;
            }
            if (!toggleHeld.add(m)) continue;
            m.toggle();
            markDirty();
            mc.ingameGUI.setRecordPlaying(m.name() + (m.enabled() ? " on" : " off"), false);
        }
    }

    public void tick() {
        for (Module m : modules) {
            if (!m.enabled()) continue;
            try {
                m.tick();
            } catch (Throwable t) {
                Guard.fail(m.name() + " tick", m, t);
            }
        }
        if (dirty) {
            dirty = false;
            saveConfig();
        }
    }

    /** Saves on the next tick, so dragging a slider doesn't write the file every frame. */
    public void markDirty() { dirty = true; }

    private static File configFile() {
        return new File(Minecraft.getMinecraft().mcDataDir, "config/duskclient-hud.json");
    }

    public void loadConfig() {
        File file = configFile();
        if (!file.isFile()) return;
        try (Reader r = new InputStreamReader(new FileInputStream(file), UTF8)) {
            Object parsed = Json.plain(new JsonParser().parse(r));
            if (!(parsed instanceof Map)) return;
            Map<?, ?> data = (Map<?, ?>) parsed;
            Set<String> mine = new HashSet<String>();
            for (Module m : modules) {
                mine.add(m.id());
                mine.addAll(m.formerIds());
                Object state = data.get(m.id());
                if (state instanceof Map) {
                    m.loadState((Map<?, ?>) state);
                    continue;
                }
                for (String old : m.formerIds()) {
                    Object former = data.get(old);
                    if (former instanceof Map) m.loadState((Map<?, ?>) former);
                }
            }
            unknown.clear();
            for (Map.Entry<?, ?> e : data.entrySet()) {
                if (!mine.contains(String.valueOf(e.getKey()))) unknown.put(String.valueOf(e.getKey()), e.getValue());
            }
        } catch (Exception e) {
            // a broken file: start from the defaults rather than crash
        }
    }

    public void saveConfig() {
        Map<String, Object> data = new LinkedHashMap<String, Object>(unknown);
        for (Module m : modules) data.put(m.id(), m.saveState());
        File file = configFile();
        file.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), UTF8)) {
            w.write(GSON.toJson(data));
        } catch (Exception e) {
            // best effort
        }
    }
}
