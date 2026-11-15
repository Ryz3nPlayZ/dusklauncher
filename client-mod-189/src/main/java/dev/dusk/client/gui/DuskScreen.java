package dev.dusk.client.gui;

import dev.dusk.client.DuskClient;
import dev.dusk.client.compat.Component;
import dev.dusk.client.compat.Input;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Screen base that hides the per-version draw entry points: subclasses
 * paint through {@link Canvas} in {@link #drawBackgroundOverlay} (after the
 * vanilla background, once per frame) and {@link #drawOverlay} (after the
 * widgets). 1.8.9 flavour: GuiScreen, with the modern Screen names
 * (init/tick/removed/onClose/isPauseScreen) mapped onto it.
 */
public abstract class DuskScreen extends GuiScreen {
    protected Component title;
    protected FontRenderer font;
    private int heldButton = -1;

    protected DuskScreen(Component title) {
        this.title = title;
    }

    public Component getTitle() {
        return title;
    }

    protected void drawBackgroundOverlay(Canvas canvas) {}

    protected void drawOverlay(Canvas canvas, int mouseX, int mouseY, float delta) {}

    /** False keeps the vanilla font in the modern look (the HUD editor previews the real HUD). */
    protected boolean sansText() {
        return true;
    }

    /** False skips vanilla's darkened/dirt background (the HUD editor wants the world visible). */
    protected boolean vanillaBackground() {
        return true;
    }

    // ---- modern lifecycle names --------------------------------------------------

    protected void init() {}

    protected void tick() {}

    protected void removed() {}

    public boolean isPauseScreen() {
        return true;
    }

    public boolean shouldCloseOnEsc() {
        return true;
    }

    public void onClose() {
        this.mc.displayGuiScreen(null);
    }

    protected void clearWidgets() {
        this.buttonList.clear();
    }

    @Override
    public final void initGui() {
        this.font = this.fontRendererObj;
        Keyboard.enableRepeatEvents(true);
        init();
    }

    @Override
    public final void updateScreen() {
        tick();
    }

    @Override
    public final void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        removed();
    }

    @Override
    public final boolean doesGuiPauseGame() {
        return isPauseScreen();
    }

    // ---- drawing -------------------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float delta) {
        if (vanillaBackground()) drawDefaultBackground();
        paint(true, mouseX, mouseY, delta);
        super.drawScreen(mouseX, mouseY, delta);
        paint(false, mouseX, mouseY, delta);
    }

    private void paint(final boolean background, final int mouseX, final int mouseY, final float delta) {
        final GraphicsCanvas canvas = new GraphicsCanvas(this.mc, this.fontRendererObj);
        try {
            Theme.paint(background, sansText(), () -> {
                if (background) drawBackgroundOverlay(canvas);
                else drawOverlay(canvas, mouseX, mouseY, delta);
            });
        } finally {
            canvas.finish();
        }
    }

    // ---- version-neutral input: screens override these, never the vanilla signatures ----

    protected boolean onClick(double mx, double my, int button) { return false; }

    protected boolean onDrag(double mx, double my, int button) { return false; }

    protected boolean onRelease(double mx, double my, int button) { return false; }

    protected boolean onScroll(double mx, double my, double amount) { return false; }

    protected boolean onKey(int key, int scancode, int modifiers) { return false; }

    protected boolean onChar(char ch) { return false; }

    /** True when the key is bound to "Open Dusk Menu". */
    protected static boolean isSettingsKey(int key, int scancode) {
        KeyBinding k = DuskClient.settingsKey();
        return k != null && key != 0 && k.getKeyCode() == key;
    }

    // ---- 1.8.9 input → the methods above -------------------------------------------

    private double mouseX() {
        return dev.dusk.client.util.HiDpi.mouse(Mouse.getEventX()) * (double) this.width / this.mc.displayWidth;
    }

    private double mouseY() {
        return this.height - dev.dusk.client.util.HiDpi.mouse(Mouse.getEventY()) * (double) this.height / this.mc.displayHeight - 1;
    }

    @Override
    public void handleMouseInput() {
        double x = mouseX(), y = mouseY();
        int button = Mouse.getEventButton();
        if (button != -1 && Mouse.getEventButtonState()) {
            heldButton = button;
            if (!onClick(x, y, button)) {
                try {
                    super.mouseClicked((int) x, (int) y, button);
                } catch (java.io.IOException ignored) {
                }
            }
        } else if (button != -1) {
            heldButton = -1;
            if (!onRelease(x, y, button)) super.mouseReleased((int) x, (int) y, button);
        } else if (heldButton != -1) {
            onDrag(x, y, heldButton);
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            // LWJGL 2 reports 120 a notch on most mice; macOS trackpads send small steps
            double amount = Math.abs(wheel) >= 60 ? wheel / 120.0 : Math.signum(wheel);
            onScroll(x, y, amount);
        }
    }

    @Override
    protected void keyTyped(char ch, int key) {
        boolean handled = key != 0 && onKey(key, key, Input.mods());
        if (ch >= ' ' && ch != 127 && !Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                && !Keyboard.isKeyDown(Keyboard.KEY_LMETA) && !Keyboard.isKeyDown(Keyboard.KEY_RMETA)) {
            handled |= onChar(ch);
        }
        if (!handled && key == Keyboard.KEY_ESCAPE && shouldCloseOnEsc()) onClose();
    }
}
