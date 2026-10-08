package dev.dusk.client.compat;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * Keyboard and mouse state, and the file picker, through GLFW. 26.3 moved
 * the window to SDL, whose key codes are scancodes; its layer has its own
 * copy. Key codes elsewhere are {@code InputConstants.KEY_*}, which carry
 * the same names on both, and mouse buttons are 0 for left.
 */
public final class Input {
    private Input() {}

    public static final int MOD_SHIFT = GLFW.GLFW_MOD_SHIFT;
    /** Ctrl, or Cmd on a Mac: either counts for a shortcut. */
    public static final int MOD_SHORTCUT = GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER;
    /** The key type a {@code KEY_*} code belongs to. */
    public static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYSYM;
    /** No key: what an unbound key holds. */
    public static final int UNKNOWN = InputConstants.UNKNOWN.getValue();

    private static long window() { return GLFW.glfwGetCurrentContext(); }

    /** False with no window (still starting). */
    public static boolean hasWindow() { return window() != 0L; }

    public static boolean keyDown(int key) {
        long w = window();
        return w != 0L && key >= 0 && GLFW.glfwGetKey(w, key) == GLFW.GLFW_PRESS;
    }

    /** {@code button} 0 is left. */
    public static boolean mouseDown(int button) {
        long w = window();
        return w != 0L && button >= 0 && GLFW.glfwGetMouseButton(w, button) == GLFW.GLFW_PRESS;
    }

    public static boolean shiftDown() {
        return keyDown(InputConstants.KEY_LSHIFT) || keyDown(InputConstants.KEY_RSHIFT);
    }

    /** Whether a key types a character (so a search box should get it before any keybind). */
    public static boolean printable(int key) {
        return key >= 32 && key <= 96 || key == 161 || key == 162 || key >= 320 && key <= 336;
    }

    /**
     * A key code saved by an older config. Codes here are what they always
     * were, so it stands.
     */
    public static int savedKey(int code) { return code; }

    /** Asks for one file matching {@code pattern} (like "*.png"); null if cancelled. Blocks, so call it off the render thread. */
    public static String openFile(String title, String pattern, String description) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8(pattern));
            filters.flip();
            return TinyFileDialogs.tinyfd_openFileDialog(title, System.getProperty("user.home") + "/", filters, description, false);
        }
    }
}
