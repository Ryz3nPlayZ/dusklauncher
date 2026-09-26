package dev.dusk.client.gui;

import dev.dusk.client.DuskClient;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.gui.widget.KeybindWidget;
import dev.dusk.client.gui.widget.LabelWidget;
import dev.dusk.client.gui.widget.ScrollPane;
import dev.dusk.client.gui.widget.TextFieldWidget;
import dev.dusk.client.gui.widget.ToggleWidget;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One page of settings, Flex-HUD style: a big title and the description
 * centred at the top, the rows in a centred scrolling column, the buttons
 * along the bottom.
 */
public class ConfigScreen extends MenuScreen {
    private static final int TITLE_SCALE = 2, TITLE_Y = 12, CONTENT_MAX_W = 300, BUTTON_W = 100, BUTTON_GAP = 8;

    private record Button(String label, boolean active, Runnable run) {}

    private final String heading;
    private final String description;
    @Nullable private final Module module;
    private final ScrollPane pane = new ScrollPane();
    private final List<Button> buttons = new ArrayList<>();
    private List<String> descLines = List.of();
    private int listTop, contentW;

    private ConfigScreen(@Nullable Screen parent, String heading, String description, @Nullable Module module) {
        super(Component.literal(heading), parent);
        this.heading = heading;
        this.description = description;
        this.module = module;
    }

    public static ConfigScreen module(@Nullable Screen parent, Module m) {
        ConfigScreen s = new ConfigScreen(parent, m.name(), m.description(), m);
        SettingsBuilder.build(s.pane, m, s.popups, () -> {});
        return s;
    }

    public static ConfigScreen preferences(@Nullable Screen parent) {
        ConfigScreen s = new ConfigScreen(parent, "Preferences", "", null);
        ScrollPane p = s.pane;
        p.add(new ToggleWidget("Animated title scene", () -> DuskConfig.get().titleScene, v -> {
            DuskConfig.get().titleScene = v;
            DuskConfig.save();
        }), SettingsBuilder.ROW_H);
        p.add(new ToggleWidget("Show account on title screen", () -> DuskConfig.get().showAccountTile, v -> {
            DuskConfig.get().showAccountTile = v;
            DuskConfig.save();
        }), SettingsBuilder.ROW_H);
        p.add(new LabelWidget(""), 6);
        p.add(new LabelWidget("Launcher background (PNG/JPG path, empty = default)"), 14);
        p.add(new TextFieldWidget(() -> DuskConfig.get().backgroundPath, v -> {
            DuskConfig.get().backgroundPath = v.trim();
            DuskConfig.save();
        }, false, 512).placeholder("C:/path/to/background.png"), 20);
        p.add(new LabelWidget(""), 6);
        p.add(new KeybindWidget("Open the Dusk menu", DuskClient::settingsKey), SettingsBuilder.ROW_H);
        return s;
    }

    @Override
    protected void init() {
        contentW = Math.min(CONTENT_MAX_W, this.width - 20);
        Minecraft mc = Minecraft.getInstance();
        descLines = description.isEmpty() ? List.of() : SettingsBuilder.wrap(mc.font, description, Math.min(360, this.width - 40));
        int y = TITLE_Y + 8 * TITLE_SCALE + 8;
        if (!descLines.isEmpty()) y += descLines.size() * 11 + 6;
        listTop = y + 4;

        buttons.clear();
        if (module != null) {
            buttons.add(new Button("Reset all", true, this::resetAll));
            if (module instanceof HudElement && inWorld()) {
                buttons.add(new Button("Edit layout", true, () -> {
                    pane.blur();
                    open(new HudEditorScreen(this));
                }));
            }
        }
        buttons.add(new Button("Done", true, this::onClose));
    }

    private void resetAll() {
        if (module == null) return;
        pane.blur();
        for (Setting<?> s : module.settings()) s.reset();
        if (module instanceof HudElement) module.setPosition(10, 10);
        saveModules();
    }

    private int listX() { return (this.width - contentW) / 2; }
    private int listBottom() { return this.height - 34; }

    private int buttonsX() {
        return (this.width - (buttons.size() * BUTTON_W + (buttons.size() - 1) * BUTTON_GAP)) / 2;
    }

    private int buttonY() { return this.height - 27; }

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        int cx = this.width / 2;
        int tw = c.textWidth(heading) * TITLE_SCALE;
        Theme.scaledText(c, heading, cx - tw / 2, TITLE_Y, Vanilla.TEXT, TITLE_SCALE, true);
        int y = TITLE_Y + 8 * TITLE_SCALE + 8;
        for (String line : descLines) {
            c.centeredText(line, cx, y, Vanilla.TEXT_OFF, true);
            y += 11;
        }

        pane.layout(listX(), listTop, contentW + 10, listBottom() - listTop);
        pane.render(c, mouseX, mouseY);

        int bx = buttonsX();
        for (Button b : buttons) {
            Vanilla.button(c, b.label, bx, buttonY(), BUTTON_W, 20, Vanilla.inside(mouseX, mouseY, bx, buttonY(), BUTTON_W, 20), b.active);
            bx += BUTTON_W + BUTTON_GAP;
        }
    }

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        int bx = buttonsX();
        for (Button b : buttons) {
            if (button == 0 && b.active && Vanilla.inside(mx, my, bx, buttonY(), BUTTON_W, 20)) {
                pane.blur();
                b.run.run();
                return true;
            }
            bx += BUTTON_W + BUTTON_GAP;
        }
        return pane.click(mx, my, button);
    }

    @Override
    protected boolean menuDrag(double mx, double my, int button) {
        return pane.drag(mx, my);
    }

    @Override
    protected boolean menuRelease(double mx, double my, int button) {
        boolean had = pane.release();
        if (had) saveModules();
        return had;
    }

    @Override
    protected boolean menuScroll(double mx, double my, double amount) {
        return pane.scroll(mx, my, amount);
    }

    @Override
    protected boolean menuKey(int key, int scancode, int modifiers) {
        return pane.keyPressed(key, modifiers);
    }

    @Override
    protected boolean menuChar(char ch) {
        return pane.charTyped(ch);
    }

    @Override
    protected void beforeClose() {
        pane.blur();
        super.beforeClose();
    }
}
