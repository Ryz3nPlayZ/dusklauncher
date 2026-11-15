package dev.dusk.client.util;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * LWJGL 2 key codes and the modern key names ("key.keyboard.v") the 1.21.11
 * config saves toggle keys under. Mouse buttons are code - 100, as in vanilla.
 */
public final class Keys {
    private static final Map<String, Integer> BY_NAME = new HashMap<String, Integer>();
    private static final Map<Integer, String> BY_CODE = new HashMap<Integer, String>();

    private Keys() {}

    private static void put(String name, int code) {
        BY_NAME.put(name, code);
        if (!BY_CODE.containsKey(code)) BY_CODE.put(code, name);
    }

    static {
        String letters = "abcdefghijklmnopqrstuvwxyz";
        int[] letterCodes = {Keyboard.KEY_A, Keyboard.KEY_B, Keyboard.KEY_C, Keyboard.KEY_D, Keyboard.KEY_E, Keyboard.KEY_F,
                Keyboard.KEY_G, Keyboard.KEY_H, Keyboard.KEY_I, Keyboard.KEY_J, Keyboard.KEY_K, Keyboard.KEY_L, Keyboard.KEY_M,
                Keyboard.KEY_N, Keyboard.KEY_O, Keyboard.KEY_P, Keyboard.KEY_Q, Keyboard.KEY_R, Keyboard.KEY_S, Keyboard.KEY_T,
                Keyboard.KEY_U, Keyboard.KEY_V, Keyboard.KEY_W, Keyboard.KEY_X, Keyboard.KEY_Y, Keyboard.KEY_Z};
        for (int i = 0; i < 26; i++) put("key.keyboard." + letters.charAt(i), letterCodes[i]);
        int[] digits = {Keyboard.KEY_0, Keyboard.KEY_1, Keyboard.KEY_2, Keyboard.KEY_3, Keyboard.KEY_4, Keyboard.KEY_5,
                Keyboard.KEY_6, Keyboard.KEY_7, Keyboard.KEY_8, Keyboard.KEY_9};
        int[] pad = {Keyboard.KEY_NUMPAD0, Keyboard.KEY_NUMPAD1, Keyboard.KEY_NUMPAD2, Keyboard.KEY_NUMPAD3, Keyboard.KEY_NUMPAD4,
                Keyboard.KEY_NUMPAD5, Keyboard.KEY_NUMPAD6, Keyboard.KEY_NUMPAD7, Keyboard.KEY_NUMPAD8, Keyboard.KEY_NUMPAD9};
        for (int i = 0; i < 10; i++) {
            put("key.keyboard." + i, digits[i]);
            put("key.keyboard.keypad." + i, pad[i]);
        }
        int[] f = {Keyboard.KEY_F1, Keyboard.KEY_F2, Keyboard.KEY_F3, Keyboard.KEY_F4, Keyboard.KEY_F5, Keyboard.KEY_F6,
                Keyboard.KEY_F7, Keyboard.KEY_F8, Keyboard.KEY_F9, Keyboard.KEY_F10, Keyboard.KEY_F11, Keyboard.KEY_F12,
                Keyboard.KEY_F13, Keyboard.KEY_F14, Keyboard.KEY_F15, Keyboard.KEY_F16, Keyboard.KEY_F17, Keyboard.KEY_F18, Keyboard.KEY_F19};
        for (int i = 0; i < f.length; i++) put("key.keyboard.f" + (i + 1), f[i]);
        put("key.keyboard.left.shift", Keyboard.KEY_LSHIFT);
        put("key.keyboard.right.shift", Keyboard.KEY_RSHIFT);
        put("key.keyboard.left.control", Keyboard.KEY_LCONTROL);
        put("key.keyboard.right.control", Keyboard.KEY_RCONTROL);
        put("key.keyboard.left.alt", Keyboard.KEY_LMENU);
        put("key.keyboard.right.alt", Keyboard.KEY_RMENU);
        put("key.keyboard.left.win", Keyboard.KEY_LMETA);
        put("key.keyboard.right.win", Keyboard.KEY_RMETA);
        put("key.keyboard.space", Keyboard.KEY_SPACE);
        put("key.keyboard.tab", Keyboard.KEY_TAB);
        put("key.keyboard.caps.lock", Keyboard.KEY_CAPITAL);
        put("key.keyboard.enter", Keyboard.KEY_RETURN);
        put("key.keyboard.escape", Keyboard.KEY_ESCAPE);
        put("key.keyboard.backspace", Keyboard.KEY_BACK);
        put("key.keyboard.up", Keyboard.KEY_UP);
        put("key.keyboard.down", Keyboard.KEY_DOWN);
        put("key.keyboard.left", Keyboard.KEY_LEFT);
        put("key.keyboard.right", Keyboard.KEY_RIGHT);
        put("key.keyboard.grave.accent", Keyboard.KEY_GRAVE);
        put("key.keyboard.minus", Keyboard.KEY_MINUS);
        put("key.keyboard.equal", Keyboard.KEY_EQUALS);
        put("key.keyboard.left.bracket", Keyboard.KEY_LBRACKET);
        put("key.keyboard.right.bracket", Keyboard.KEY_RBRACKET);
        put("key.keyboard.backslash", Keyboard.KEY_BACKSLASH);
        put("key.keyboard.semicolon", Keyboard.KEY_SEMICOLON);
        put("key.keyboard.apostrophe", Keyboard.KEY_APOSTROPHE);
        put("key.keyboard.comma", Keyboard.KEY_COMMA);
        put("key.keyboard.period", Keyboard.KEY_PERIOD);
        put("key.keyboard.slash", Keyboard.KEY_SLASH);
        put("key.keyboard.insert", Keyboard.KEY_INSERT);
        put("key.keyboard.delete", Keyboard.KEY_DELETE);
        put("key.keyboard.home", Keyboard.KEY_HOME);
        put("key.keyboard.end", Keyboard.KEY_END);
        put("key.keyboard.page.up", Keyboard.KEY_PRIOR);
        put("key.keyboard.page.down", Keyboard.KEY_NEXT);
        put("key.keyboard.num.lock", Keyboard.KEY_NUMLOCK);
        put("key.keyboard.scroll.lock", Keyboard.KEY_SCROLL);
        put("key.keyboard.pause", Keyboard.KEY_PAUSE);
        put("key.keyboard.keypad.add", Keyboard.KEY_ADD);
        put("key.keyboard.keypad.subtract", Keyboard.KEY_SUBTRACT);
        put("key.keyboard.keypad.multiply", Keyboard.KEY_MULTIPLY);
        put("key.keyboard.keypad.divide", Keyboard.KEY_DIVIDE);
        put("key.keyboard.keypad.decimal", Keyboard.KEY_DECIMAL);
        put("key.keyboard.keypad.enter", Keyboard.KEY_NUMPADENTER);
        put("key.keyboard.keypad.equal", Keyboard.KEY_NUMPADEQUALS);
        put("key.mouse.left", -100);
        put("key.mouse.right", -99);
        put("key.mouse.middle", -98);
        for (int i = 4; i <= 8; i++) put("key.mouse." + i, -100 + i - 1);
    }

    /** The code for a modern key name, or 0 (unbound) for one 1.8.9 doesn't know. */
    public static int code(String name) {
        Integer c = BY_NAME.get(name.toLowerCase(Locale.ROOT));
        return c == null ? 0 : c;
    }

    public static String name(int code) {
        String n = BY_CODE.get(code);
        return n != null ? n : "key.keyboard.unknown";
    }

    /** "V", "Right Ctrl", "Mouse 4"; "None" when unbound. */
    public static String display(int code) {
        if (code == 0) return "None";
        if (code < 0) {
            int b = code + 100;
            return b == 0 ? "Left Click" : b == 1 ? "Right Click" : b == 2 ? "Middle Click" : "Mouse " + (b + 1);
        }
        switch (code) {
            case Keyboard.KEY_LCONTROL: return "Left Ctrl";
            case Keyboard.KEY_RCONTROL: return "Right Ctrl";
            case Keyboard.KEY_LSHIFT: return "Left Shift";
            case Keyboard.KEY_RSHIFT: return "Right Shift";
            case Keyboard.KEY_LMENU: return "Left Alt";
            case Keyboard.KEY_RMENU: return "Right Alt";
            case Keyboard.KEY_LMETA: return "Left Cmd";
            case Keyboard.KEY_RMETA: return "Right Cmd";
            default:
                String n = Keyboard.getKeyName(code);
                return n == null ? "Key " + code : n.length() == 1 ? n : n.charAt(0) + n.substring(1).toLowerCase(Locale.ROOT);
        }
    }

    public static boolean isDown(int code) {
        if (code == 0) return false;
        if (code < 0) {
            int b = code + 100;
            return b < Mouse.getButtonCount() && Mouse.isButtonDown(b);
        }
        return code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
    }
}
