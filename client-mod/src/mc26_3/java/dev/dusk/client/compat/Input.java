package dev.dusk.client.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * Keyboard and mouse state, and the file picker, through SDL, which 26.3's
 * window runs on. Key codes are SDL scancodes behind the same
 * {@code InputConstants.KEY_*} names older versions use for GLFW's; mouse
 * buttons are 0 for left here too.
 */
public final class Input {
    private Input() {}

    public static final int MOD_SHIFT = InputConstants.MOD_SHIFT;
    /** Ctrl, or Cmd on a Mac: either counts for a shortcut. */
    public static final int MOD_SHORTCUT = InputConstants.MOD_CONTROL | InputConstants.MOD_SUPER;
    /** The key type a {@code KEY_*} code belongs to. */
    public static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYBOARD;
    /** No key: what an unbound key holds. */
    public static final int UNKNOWN = InputConstants.UNKNOWN.getValue();

    /** False with no window (still starting). */
    public static boolean hasWindow() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.getWindow() != null;
    }

    public static boolean keyDown(int key) {
        return key > 0 && hasWindow() && InputConstants.isKeyDown(key);
    }

    /** {@code button} 0 is left (SDL counts from 1). */
    public static boolean mouseDown(int button) {
        if (button < 0 || button > 30 || !hasWindow()) return false;
        return (SDLMouse.SDL_GetMouseState(null, null) & (1 << button)) != 0;
    }

    public static boolean shiftDown() {
        return keyDown(InputConstants.KEY_LSHIFT) || keyDown(InputConstants.KEY_RSHIFT);
    }

    /** Whether a key types a character (so a search box should get it before any keybind). */
    public static boolean printable(int key) {
        return key >= 4 && key <= 39 // letters and digits
                || key >= 44 && key <= 56 // space and punctuation
                || key >= 84 && key <= 87 || key >= 89 && key <= 100 // the keypad but its Enter
                || key == 103;
    }

    /**
     * A key code saved by an older config, which was GLFW's. Letters, digits
     * and F1-F12 come across; anything else is left unbound.
     */
    public static int savedKey(int code) {
        if (code >= 65 && code <= 90) return code - 65 + 4; // A-Z
        if (code >= 49 && code <= 57) return code - 49 + 30; // 1-9
        if (code == 48) return 39; // 0
        if (code >= 290 && code <= 301) return code - 290 + 58; // F1-F12
        return UNKNOWN;
    }

    /**
     * Asks for one file matching {@code pattern} (like "*.png"); null if
     * cancelled. Blocks, so call it off the render thread: SDL opens the
     * dialog from the main thread and answers there.
     */
    public static String openFile(String title, String pattern, String description) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) throw new IllegalStateException("openFile blocks; call it off the render thread");
        CompletableFuture<String> picked = new CompletableFuture<>();
        mc.execute(() -> {
            // SDL keeps the filter until it answers, so it lives off the stack and is freed in the answer
            ByteBuffer name = MemoryUtil.memUTF8(description);
            ByteBuffer ext = MemoryUtil.memUTF8(pattern.replace("*.", ""));
            SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.calloc(1);
            filters.get(0).name(name).pattern(ext);
            SDL_DialogFileCallback[] self = new SDL_DialogFileCallback[1];
            self[0] = SDL_DialogFileCallback.create((userdata, files, filter) -> {
                String path = null;
                if (files != 0L) {
                    long first = MemoryUtil.memGetAddress(files);
                    if (first != 0L) path = MemoryUtil.memUTF8(first);
                }
                picked.complete(path);
                filters.free();
                MemoryUtil.memFree(name);
                MemoryUtil.memFree(ext);
                self[0].free();
            });
            SDLDialog.SDL_ShowOpenFileDialog(self[0], 0L, mc.getWindow().handle(), filters,
                    System.getProperty("user.home") + "/", false);
        });
        return picked.join();
    }
}
