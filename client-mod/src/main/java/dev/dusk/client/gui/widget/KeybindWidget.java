package dev.dusk.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Vanilla;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.function.Supplier;

/**
 * A key binding row, like vanilla's Controls entry: click the button, press a
 * key. Esc cancels; the change is the same binding Controls edits, saved to
 * options.txt.
 */
public class KeybindWidget extends SettingRow {
    private final Supplier<KeyMapping> mapping;

    public KeybindWidget(String label, Supplier<KeyMapping> mapping) {
        super(label);
        this.mapping = mapping;
    }

    @Override
    protected void renderControl(Canvas c, int mouseX, int mouseY) {
        KeyMapping k = mapping.get();
        String text = focused ? "> Press a key <" : k == null ? "" : k.getTranslatedKeyMessage().getString();
        Vanilla.button(c, text, controlX(), top(), CONTROL_W, SQUARE, inControl(mouseX, mouseY) || focused, true);
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
        KeyMapping k = mapping.get();
        if (key != GLFW.GLFW_KEY_ESCAPE && k != null) {
            k.setKey(InputConstants.Type.KEYSYM.getOrCreate(key));
            KeyMapping.resetMapping();
            Minecraft.getInstance().options.save();
        }
        setFocused(false);
        return true;
    }
}
