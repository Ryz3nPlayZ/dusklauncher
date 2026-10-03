package dev.dusk.client.module.setting;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * The key that switches a module on and off, unbound by default. Every
 * module has one; unlike {@link KeySetting} it is not a vanilla key mapping,
 * so seventy unbound rows don't land in Controls. Kept in the module config.
 */
public class ToggleKeySetting extends Setting<Integer> {
    public ToggleKeySetting() {
        super("toggleKey", "Toggle key", GLFW.GLFW_KEY_UNKNOWN);
    }

    public boolean bound() { return value != GLFW.GLFW_KEY_UNKNOWN; }

    /** "None" when unbound, else the key as Controls names it. */
    public String keyName() {
        return bound() ? InputConstants.Type.KEYSYM.getOrCreate(value).getDisplayName().getString() : "None";
    }

    @Override
    public Object save() { return bound() ? value : null; }

    @Override
    public void load(Object raw) {
        if (raw instanceof Number n) value = n.intValue();
    }
}
