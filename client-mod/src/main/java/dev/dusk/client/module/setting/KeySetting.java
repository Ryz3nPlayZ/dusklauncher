package dev.dusk.client.module.setting;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.compat.Input;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * A module's key, shown on its settings page. It is a vanilla key mapping
 * (key.duskclient.{@code id}, also listed under Controls), so options.txt
 * keeps it rather than the module config. Construct it during client init.
 */
public class KeySetting extends Setting<String> {
    private final KeyMapping mapping;

    public KeySetting(String id, String name, int defaultKey) {
        super(id, name, Input.KEYBOARD.getOrCreate(defaultKey).getName());
        this.mapping = Compat.registerKey("key.duskclient." + id, defaultKey);
    }

    public KeyMapping mapping() { return mapping; }

    @Override
    public String get() { return mapping.saveString(); }

    @Override
    public void reset() {
        mapping.setKey(mapping.getDefaultKey());
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }

    @Override
    public Object save() { return null; }

    @Override
    public void load(Object raw) {}
}
