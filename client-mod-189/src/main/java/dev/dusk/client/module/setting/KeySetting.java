package dev.dusk.client.module.setting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;

/**
 * A module's key: a vanilla key binding (key.duskclient.id, also listed under
 * Controls), so options.txt keeps it rather than the module config.
 */
public class KeySetting extends Setting<Integer> {
    private final KeyBinding binding;

    public KeySetting(String id, String name, int defaultKey) {
        super(id, name, defaultKey);
        this.binding = new KeyBinding("key.duskclient." + id, defaultKey, "Dusk Client");
        ClientRegistry.registerKeyBinding(binding);
    }

    public KeyBinding binding() { return binding; }

    /** The modern build's name for {@link #binding}. */
    public KeyBinding mapping() { return binding; }

    @Override
    public Integer get() { return binding.getKeyCode(); }

    @Override
    public void set(Integer code) {
        binding.setKeyCode(code);
        KeyBinding.resetKeyBindingArrayAndHash();
        Minecraft.getMinecraft().gameSettings.saveOptions();
    }

    @Override
    public void reset() { set(binding.getKeyCodeDefault()); }

    /** Whether the key is physically held right now (works for mouse buttons too). */
    public boolean held() { return dev.dusk.client.util.Keys.isDown(binding.getKeyCode()); }

    @Override
    public Object save() { return null; }

    @Override
    public void load(Object raw) {}
}
