package dev.dusk.client.compat;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Keyboard and mouse state for the menu code shared with 1.21.11, over
 * LWJGL 2. Modifier bits keep GLFW's layout (1 shift, 2 ctrl, 4 alt, 8 super)
 * so the shared code reads them unchanged.
 */
public final class Input {
    private Input() {}

    public static final int MOD_SHIFT = 1, MOD_CONTROL = 2, MOD_ALT = 4, MOD_SUPER = 8;
    /** Ctrl, or Cmd on a Mac: either counts for a shortcut. */
    public static final int MOD_SHORTCUT = MOD_CONTROL | MOD_SUPER;
    public static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYSYM;
    /** No key: what an unbound key holds. */
    public static final int UNKNOWN = Keyboard.KEY_NONE;
    public static final int KEY_LSUPER = Keyboard.KEY_LMETA, KEY_RSUPER = Keyboard.KEY_RMETA, KEY_MENU = Keyboard.KEY_APPS,
            KEY_NUM_DIVIDE = Keyboard.KEY_DIVIDE, KEY_NUM_SUBTRACT = Keyboard.KEY_SUBTRACT, KEY_NUM_DECIMAL = Keyboard.KEY_DECIMAL;

    public static InputConstants.Key mouse(int button) { return InputConstants.Type.MOUSE.getOrCreate(button); }

    public static boolean hasWindow() { return Keyboard.isCreated(); }

    public static boolean keyDown(int key) {
        return key > 0 && key < Keyboard.KEYBOARD_SIZE && Keyboard.isCreated() && Keyboard.isKeyDown(key);
    }

    public static boolean mouseDown(int button) {
        return button >= 0 && button < Mouse.getButtonCount() && Mouse.isButtonDown(button);
    }

    public static boolean shiftDown() {
        return keyDown(Keyboard.KEY_LSHIFT) || keyDown(Keyboard.KEY_RSHIFT);
    }

    /** The modifiers held now, as GLFW bits. */
    public static int mods() {
        int m = 0;
        if (shiftDown()) m |= MOD_SHIFT;
        if (keyDown(Keyboard.KEY_LCONTROL) || keyDown(Keyboard.KEY_RCONTROL)) m |= MOD_CONTROL;
        if (keyDown(Keyboard.KEY_LMENU) || keyDown(Keyboard.KEY_RMENU)) m |= MOD_ALT;
        if (keyDown(Keyboard.KEY_LMETA) || keyDown(Keyboard.KEY_RMETA)) m |= MOD_SUPER;
        return m;
    }

    /** Whether a key types a character (so a search box should get it before any keybind). */
    public static boolean printable(int key) {
        switch (key) {
            case Keyboard.KEY_SPACE: case Keyboard.KEY_MINUS: case Keyboard.KEY_EQUALS: case Keyboard.KEY_LBRACKET:
            case Keyboard.KEY_RBRACKET: case Keyboard.KEY_BACKSLASH: case Keyboard.KEY_SEMICOLON: case Keyboard.KEY_APOSTROPHE:
            case Keyboard.KEY_GRAVE: case Keyboard.KEY_COMMA: case Keyboard.KEY_PERIOD: case Keyboard.KEY_SLASH:
            case Keyboard.KEY_ADD: case Keyboard.KEY_SUBTRACT: case Keyboard.KEY_MULTIPLY: case Keyboard.KEY_DIVIDE:
            case Keyboard.KEY_DECIMAL:
                return true;
            default:
                break;
        }
        if (key >= Keyboard.KEY_1 && key <= Keyboard.KEY_0) return true;
        if (key >= Keyboard.KEY_Q && key <= Keyboard.KEY_P) return true;
        if (key >= Keyboard.KEY_A && key <= Keyboard.KEY_L) return true;
        if (key >= Keyboard.KEY_Z && key <= Keyboard.KEY_M) return true;
        if (key >= Keyboard.KEY_NUMPAD7 && key <= Keyboard.KEY_NUMPAD0 && key != Keyboard.KEY_SUBTRACT && key != Keyboard.KEY_ADD) return true;
        return false;
    }

    public static int savedKey(int code) { return code; }
}
