package dev.dusk.client.hud;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Physical key state, which is not what {@link KeyMapping#isDown} reports once a toggle holds the key down. */
public final class Keys {
    private Keys() {}

    public static boolean physicallyDown(KeyMapping mapping) {
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) return false;
        InputConstants.Key key;
        try {
            key = InputConstants.getKey(mapping.saveString());
        } catch (IllegalArgumentException e) {
            return false;
        }
        int code = key.getValue();
        if (code < 0) return false;
        if (key.getType() == InputConstants.Type.MOUSE) return GLFW.glfwGetMouseButton(window, code) == GLFW.GLFW_PRESS;
        if (key.getType() == InputConstants.Type.KEYSYM) return GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS;
        return false;
    }
}
