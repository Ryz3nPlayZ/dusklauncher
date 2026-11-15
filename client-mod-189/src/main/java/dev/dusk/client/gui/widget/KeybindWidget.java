package dev.dusk.client.gui.widget;

import dev.dusk.client.compat.InputConstants;
import dev.dusk.client.compat.Input;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Px;
import dev.dusk.client.gui.Theme;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.Minecraft;

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

    public KeybindWidget(String label, Supplier<KeyBinding> mapping) {
        super(label);
        this.keyName = () -> {
            KeyBinding k = mapping.get();
            return k == null ? "" : dev.dusk.client.compat.Binds.label(k);
        };
        this.bind = key -> {
            KeyBinding k = mapping.get();
            if (k == null) return;
            dev.dusk.client.compat.Binds.set(k, InputConstants.Key.ofCode(key));
            dev.dusk.client.compat.Binds.save();
        };
    }

    /** A plain key code; {@code bind} gets {@link Input#UNKNOWN} when cleared. */
    public KeybindWidget(String label, Supplier<String> keyName, IntConsumer bind) {
        super(label);
        this.keyName = keyName;
        this.bind = key -> bind.accept(key == InputConstants.KEY_BACKSPACE || key == InputConstants.KEY_DELETE
                ? Input.UNKNOWN : key);
    }

    @Override
    protected void renderControl(Canvas c, int mouseX, int mouseY) {
        boolean hot = inControl(mouseX, mouseY) || focused;
        if (Theme.modern()) {
            // a pill holding the key; listening, it lights white and asks for one
            String key = keyName.get();
            Px.box(c, controlX(), top(), CONTROL_W, SQUARE, focused ? Theme.Family.ACCENT : Theme.Family.GREY, hot, false, 0xFF);
            Px.sans(c, focused ? "Press a key" : key.isEmpty() ? "None" : key, controlX(), top(), CONTROL_W, SQUARE, Px.Size.S16,
                    focused ? Theme.INK : key.isEmpty() ? Theme.TEXT_FAINT : hot ? Theme.TEXT : Theme.TEXT_MUTED, false, false);
            return;
        }
        String text = focused ? "> Press a key <" : keyName.get();
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
        if (key != InputConstants.KEY_ESCAPE) bind.accept(key);
        setFocused(false);
        return true;
    }
}
