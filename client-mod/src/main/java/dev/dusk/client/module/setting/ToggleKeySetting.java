package dev.dusk.client.module.setting;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.compat.Input;

/**
 * The key that switches a module on and off, unbound by default. Every
 * module has one; unlike {@link KeySetting} it is not a vanilla key mapping,
 * so seventy unbound rows don't land in Controls. Kept in the module config.
 */
public class ToggleKeySetting extends Setting<Integer> {
    public ToggleKeySetting() {
        super("toggleKey", "Toggle key", Input.UNKNOWN);
    }

    public boolean bound() { return value != Input.UNKNOWN; }

    /** "None" when unbound, else the key as Controls names it. */
    public String keyName() {
        return bound() ? Input.KEYBOARD.getOrCreate(value).getDisplayName().getString() : "None";
    }

    /** By name ("key.keyboard.v"), which is the same key on every version; codes are not. */
    @Override
    public Object save() { return bound() ? Input.KEYBOARD.getOrCreate(value).getName() : null; }

    @Override
    public void load(Object raw) {
        if (raw instanceof Number n) {
            value = Input.savedKey(n.intValue());
        } else if (raw instanceof String name) {
            try {
                InputConstants.Key key = InputConstants.getKey(name);
                if (key.getType() == Input.KEYBOARD) value = key.getValue();
            } catch (IllegalArgumentException ignored) {
                // a name this version doesn't know: left unbound
            }
        }
    }
}
