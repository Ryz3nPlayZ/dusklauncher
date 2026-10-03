package dev.dusk.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Px;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * A key binding row, like vanilla's Controls entry: click the button, press a
 * key. Esc cancels. Bound to a key mapping, the change is the same binding
 * Controls edits, saved to options.txt; bound to a plain key code (a
 * module's toggle key), Backspace or Delete clears it.
 */
public class KeybindWidget extends SettingRow {
    private final Supplier<String> keyName;
    private final IntConsumer bind;

    public KeybindWidget(String label, Supplier<KeyMapping> mapping) {
        super(label);
        this.keyName = () -> {
            KeyMapping k = mapping.get();
            return k == null ? "" : k.getTranslatedKeyMessage().getString();
        };
        this.bind = key -> {
            KeyMapping k = mapping.get();
            if (k == null) return;
            k.setKey(InputConstants.Type.KEYSYM.getOrCreate(key));
            KeyMapping.resetMapping();
            Minecraft.getInstance().options.save();
        };
    }

    /** A plain key code; {@code bind} gets GLFW_KEY_UNKNOWN when cleared. */
    public KeybindWidget(String label, Supplier<String> keyName, IntConsumer bind) {
        super(label);
        this.keyName = keyName;
        this.bind = key -> bind.accept(key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE
                ? GLFW.GLFW_KEY_UNKNOWN : key);
    }

    @Override
    protected void renderControl(Canvas c, int mouseX, int mouseY) {
        String text = focused ? "> Press a key <" : keyName.get();
        boolean hot = inControl(mouseX, mouseY) || focused;
        Px.button(c, controlX(), top(), CONTROL_W, SQUARE, hot, true);
        Px.label(c, text, controlX(), top(), CONTROL_W, SQUARE, Px.Size.S16, focused ? Px.Tone.ACCENT : Px.Tone.GREY,
                hot, false, false, 0xFF);
    }

    @Override
    protected boolean clickControl(double mx, double my, int button) {
        if (button != 0 || !inControl(mx, my)) return false;
        setFocused(!focused);
        return true;
    }

    @Override
    public boolean click(double mx, double my, int button) {
        boolean hit = super.click(mx, my, button);
        if (!hit) setFocused(false);
        return hit;
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        if (!focused) return false;
        if (key != GLFW.GLFW_KEY_ESCAPE) bind.accept(key);
        setFocused(false);
        return true;
    }
}
