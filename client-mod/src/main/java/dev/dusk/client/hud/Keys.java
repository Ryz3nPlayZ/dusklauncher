package dev.dusk.client.hud;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.compat.Input;
import net.minecraft.client.KeyMapping;

/** Physical key state, which is not what {@link KeyMapping#isDown} reports once a toggle holds the key down. */
public final class Keys {
    private Keys() {}

    public static boolean physicallyDown(KeyMapping mapping) {
        InputConstants.Key key;
        try {
            key = InputConstants.getKey(mapping.saveString());
        } catch (IllegalArgumentException e) {
            return false;
        }
        int code = key.getValue();
        if (code < 0) return false;
        if (key.getType() == InputConstants.Type.MOUSE) return Input.mouseDown(code);
        if (key.getType() == Input.KEYBOARD) return Input.keyDown(code);
        return false;
    }
}
