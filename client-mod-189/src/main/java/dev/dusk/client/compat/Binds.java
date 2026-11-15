package dev.dusk.client.compat;

import dev.dusk.client.util.Keys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/** 1.21.11's KeyMapping calls, over a 1.8.9 KeyBinding (a key code, 0 unbound, mouse button - 100). */
public final class Binds {
    private Binds() {}

    public static boolean unbound(KeyBinding k) { return k.getKeyCode() == 0; }

    /** The modern saved name ("key.keyboard.r"). */
    public static String saveString(KeyBinding k) { return Keys.name(k.getKeyCode()); }

    /** "R", "Left Click"; "None" unbound. */
    public static String label(KeyBinding k) { return Keys.display(k.getKeyCode()); }

    public static boolean isDefault(KeyBinding k) { return k.getKeyCode() == k.getKeyCodeDefault(); }

    public static boolean same(KeyBinding a, KeyBinding b) { return a.getKeyCode() == b.getKeyCode(); }

    public static InputConstants.Key key(KeyBinding k) { return InputConstants.Key.ofCode(k.getKeyCode()); }

    public static InputConstants.Key defaultKey(KeyBinding k) { return InputConstants.Key.ofCode(k.getKeyCodeDefault()); }

    public static void set(KeyBinding k, InputConstants.Key key) { k.setKeyCode(key.code()); }

    /** Rebuilds the key lookup and writes options.txt, after any rebind. */
    public static void save() {
        KeyBinding.resetKeyBindingArrayAndHash();
        Minecraft.getMinecraft().gameSettings.saveOptions();
    }

    public static KeyBinding[] all() { return Minecraft.getMinecraft().gameSettings.keyBindings; }
}
