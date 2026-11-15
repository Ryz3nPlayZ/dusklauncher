package dev.dusk.client.compat;

import dev.dusk.client.util.Keys;
import org.lwjgl.input.Keyboard;

/**
 * The modern key names over LWJGL 2's codes, for the menu code shared with
 * 1.21.11. Every key code in the 1.8.9 build is an LWJGL 2 code; a key
 * binding holds a mouse button as button - 100, as vanilla does.
 */
public final class InputConstants {
    private InputConstants() {}

    public static final int KEY_A = Keyboard.KEY_A, KEY_B = Keyboard.KEY_B, KEY_C = Keyboard.KEY_C, KEY_D = Keyboard.KEY_D,
            KEY_E = Keyboard.KEY_E, KEY_F = Keyboard.KEY_F, KEY_G = Keyboard.KEY_G, KEY_H = Keyboard.KEY_H, KEY_I = Keyboard.KEY_I,
            KEY_J = Keyboard.KEY_J, KEY_K = Keyboard.KEY_K, KEY_L = Keyboard.KEY_L, KEY_M = Keyboard.KEY_M, KEY_N = Keyboard.KEY_N,
            KEY_O = Keyboard.KEY_O, KEY_P = Keyboard.KEY_P, KEY_Q = Keyboard.KEY_Q, KEY_R = Keyboard.KEY_R, KEY_S = Keyboard.KEY_S,
            KEY_T = Keyboard.KEY_T, KEY_U = Keyboard.KEY_U, KEY_V = Keyboard.KEY_V, KEY_W = Keyboard.KEY_W, KEY_X = Keyboard.KEY_X,
            KEY_Y = Keyboard.KEY_Y, KEY_Z = Keyboard.KEY_Z;
    public static final int KEY_0 = Keyboard.KEY_0, KEY_1 = Keyboard.KEY_1, KEY_2 = Keyboard.KEY_2, KEY_3 = Keyboard.KEY_3,
            KEY_4 = Keyboard.KEY_4, KEY_5 = Keyboard.KEY_5, KEY_6 = Keyboard.KEY_6, KEY_7 = Keyboard.KEY_7, KEY_8 = Keyboard.KEY_8,
            KEY_9 = Keyboard.KEY_9;
    public static final int KEY_F1 = Keyboard.KEY_F1, KEY_F2 = Keyboard.KEY_F2, KEY_F3 = Keyboard.KEY_F3, KEY_F4 = Keyboard.KEY_F4,
            KEY_F5 = Keyboard.KEY_F5, KEY_F6 = Keyboard.KEY_F6, KEY_F7 = Keyboard.KEY_F7, KEY_F8 = Keyboard.KEY_F8,
            KEY_F9 = Keyboard.KEY_F9, KEY_F10 = Keyboard.KEY_F10, KEY_F11 = Keyboard.KEY_F11, KEY_F12 = Keyboard.KEY_F12;
    public static final int KEY_NUMPAD0 = Keyboard.KEY_NUMPAD0, KEY_NUMPAD1 = Keyboard.KEY_NUMPAD1, KEY_NUMPAD2 = Keyboard.KEY_NUMPAD2,
            KEY_NUMPAD3 = Keyboard.KEY_NUMPAD3, KEY_NUMPAD4 = Keyboard.KEY_NUMPAD4, KEY_NUMPAD5 = Keyboard.KEY_NUMPAD5,
            KEY_NUMPAD6 = Keyboard.KEY_NUMPAD6, KEY_NUMPAD7 = Keyboard.KEY_NUMPAD7, KEY_NUMPAD8 = Keyboard.KEY_NUMPAD8,
            KEY_NUMPAD9 = Keyboard.KEY_NUMPAD9, KEY_NUMPADENTER = Keyboard.KEY_NUMPADENTER, KEY_NUMLOCK = Keyboard.KEY_NUMLOCK,
            KEY_ADD = Keyboard.KEY_ADD, KEY_MULTIPLY = Keyboard.KEY_MULTIPLY;
    public static final int KEY_ESCAPE = Keyboard.KEY_ESCAPE, KEY_RETURN = Keyboard.KEY_RETURN, KEY_TAB = Keyboard.KEY_TAB,
            KEY_SPACE = Keyboard.KEY_SPACE, KEY_BACKSPACE = Keyboard.KEY_BACK, KEY_DELETE = Keyboard.KEY_DELETE,
            KEY_INSERT = Keyboard.KEY_INSERT, KEY_HOME = Keyboard.KEY_HOME, KEY_END = Keyboard.KEY_END,
            KEY_PAGEUP = Keyboard.KEY_PRIOR, KEY_PAGEDOWN = Keyboard.KEY_NEXT, KEY_UP = Keyboard.KEY_UP, KEY_DOWN = Keyboard.KEY_DOWN,
            KEY_LEFT = Keyboard.KEY_LEFT, KEY_RIGHT = Keyboard.KEY_RIGHT, KEY_LSHIFT = Keyboard.KEY_LSHIFT,
            KEY_RSHIFT = Keyboard.KEY_RSHIFT, KEY_LCONTROL = Keyboard.KEY_LCONTROL, KEY_RCONTROL = Keyboard.KEY_RCONTROL,
            KEY_LALT = Keyboard.KEY_LMENU, KEY_RALT = Keyboard.KEY_RMENU, KEY_CAPSLOCK = Keyboard.KEY_CAPITAL,
            KEY_SCROLLLOCK = Keyboard.KEY_SCROLL, KEY_PAUSE = Keyboard.KEY_PAUSE, KEY_PRINTSCREEN = Keyboard.KEY_SYSRQ,
            KEY_GRAVE = Keyboard.KEY_GRAVE, KEY_MINUS = Keyboard.KEY_MINUS, KEY_EQUALS = Keyboard.KEY_EQUALS,
            KEY_LBRACKET = Keyboard.KEY_LBRACKET, KEY_RBRACKET = Keyboard.KEY_RBRACKET, KEY_BACKSLASH = Keyboard.KEY_BACKSLASH,
            KEY_SEMICOLON = Keyboard.KEY_SEMICOLON, KEY_APOSTROPHE = Keyboard.KEY_APOSTROPHE, KEY_COMMA = Keyboard.KEY_COMMA,
            KEY_PERIOD = Keyboard.KEY_PERIOD, KEY_SLASH = Keyboard.KEY_SLASH;
    public static final int MOUSE_BUTTON_LEFT = 0, MOUSE_BUTTON_RIGHT = 1, MOUSE_BUTTON_MIDDLE = 2;

    public enum Type {
        KEYSYM, MOUSE;

        public Key getOrCreate(int value) { return new Key(this, value); }
    }

    /** A key or mouse button, as a key binding can hold it. */
    public static final class Key {
        private final Type type;
        private final int value;

        Key(Type type, int value) {
            this.type = type;
            this.value = value;
        }

        public Type getType() { return type; }

        public int getValue() { return value; }

        /** The code a 1.8.9 KeyBinding stores. */
        public int code() { return type == Type.MOUSE ? value - 100 : value; }

        public static Key ofCode(int code) { return code < 0 ? new Key(Type.MOUSE, code + 100) : new Key(Type.KEYSYM, code); }

        /** The modern name ("key.keyboard.v"), as 1.21.11's configs save it. */
        public String getName() { return code() == 0 ? "key.keyboard.unknown" : Keys.name(code()); }

        public Component getDisplayName() { return Component.literal(Keys.display(code())); }

        @Override
        public boolean equals(Object o) { return o instanceof Key && ((Key) o).type == type && ((Key) o).value == value; }

        @Override
        public int hashCode() { return type.hashCode() * 31 + value; }
    }

    public static final Key UNKNOWN = new Key(Type.KEYSYM, Keyboard.KEY_NONE);
}
