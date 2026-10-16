package dev.dusk.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.DuskClient;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.compat.Input;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.gui.widget.TextFieldWidget;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.ToggleKeySetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/* Hallmark · pre-emit critique: P4 H4 E4 S4 R5 V4 */
/**
 * Every key binding on a drawn keyboard (60%, 75% or full size, the pick
 * remembered), the way Studious Keybinds shows them: a key's outline is
 * green with one action on it, red with more than one, grey with none, and
 * hovering it lists them. Click a key to see what it does, unbind or reset
 * each action on it, or give it another action from the list; with no key
 * picked, click an action's key to press (or click) a new one. Covers every
 * mapping in Controls, other mods' included, and each module's toggle key.
 * Search matches an action, its category or its key.
 */
public class KeyboardScreen extends PanelScreen {
    private static final String[] TABS = {"60%", "75%", "100%"};
    private static final String[] LAYOUTS = {"60", "75", "100"};
    private static final int ROW_H = 26, ROW_GAP = 3, HEAD_H = 22, BTN_W = 52, BTN_H = 16, PILL_W = 78, LEGEND_H = 14;
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    /** One key drawn on the board, in key units from its top left. */
    private record Cap(double x, double y, double w, double h, String label, InputConstants.Key key) {}

    /** One binding: a Controls mapping, or a module's toggle key. */
    private static final class Action {
        final String name, category;
        @Nullable final KeyMapping mapping;
        @Nullable final ToggleKeySetting toggle;

        Action(KeyMapping mapping) {
            this.mapping = mapping;
            this.toggle = null;
            this.name = I18n.get(mapping.getName());
            this.category = Compat.keyCategoryLabel(mapping);
        }

        Action(Module m) {
            this.mapping = null;
            this.toggle = m.toggleKey();
            this.name = "Toggle " + m.name();
            this.category = "Dusk modules";
        }

        /** The bound key's name ("key.keyboard.r"), or "" unbound. */
        String keyId() {
            if (mapping != null) return mapping.isUnbound() ? "" : mapping.saveString();
            return toggle.bound() ? Input.KEYBOARD.getOrCreate(toggle.get()).getName() : "";
        }

        String keyLabel() {
            if (mapping != null) return mapping.isUnbound() ? "None" : mapping.getTranslatedKeyMessage().getString();
            return toggle.keyName();
        }

        boolean isDefault() {
            return mapping != null ? mapping.isDefault() : !toggle.bound();
        }

        /** False when this binding can't hold {@code key} (a module toggle and a mouse button). */
        boolean canHold(InputConstants.Key key) {
            return mapping != null || key.getType() == Input.KEYBOARD;
        }

        void bind(InputConstants.Key key) {
            if (mapping != null) {
                mapping.setKey(key);
                saveMappings();
            } else if (key.getType() == Input.KEYBOARD) {
                toggle.set(key.getValue());
                MenuScreen.saveModules();
            }
        }

        void unbind() {
            if (mapping != null) {
                mapping.setKey(InputConstants.UNKNOWN);
                saveMappings();
            } else {
                toggle.set(Input.UNKNOWN);
                MenuScreen.saveModules();
            }
        }

        void reset() {
            if (mapping != null) {
                mapping.setKey(mapping.getDefaultKey());
                saveMappings();
            } else {
                toggle.reset();
                MenuScreen.saveModules();
            }
        }

        private static void saveMappings() {
            KeyMapping.resetMapping();
            Minecraft.getInstance().options.save();
        }
    }

    /** A clickable box laid out this frame, in screen pixels. */
    private record Hit(String what, @Nullable Action action, int x, int y, int w, int h) {
        boolean in(double mx, double my) { return Vanilla.inside(mx, my, x, y, w, h); }
    }

    private final TextFieldWidget search;
    private String query = "";
    private int layout;
    private List<Cap> caps = List.of();
    private final List<Action> actions = new ArrayList<>();
    /** Actions by the key they sit on, rebuilt every frame (other screens and mods can rebind too). */
    private final Map<String, List<Action>> byKey = new HashMap<>();
    @Nullable private InputConstants.Key selected;
    /** The action waiting for a key press. */
    @Nullable private Action listening;
    /** A key press just bound something: the character it types goes nowhere. */
    private boolean swallowChar;
    @Nullable private String notice;

    // laid out each frame
    private int unit, boardX, boardY, boardW, boardH, lowerY;
    private final List<Hit> hits = new ArrayList<>();
    private final List<Action> bound = new ArrayList<>(), others = new ArrayList<>();

    public KeyboardScreen(@Nullable Screen parent) {
        super(Component.literal("Keyboard"), parent);
        this.search = new TextFieldWidget(() -> "", q -> {
            query = q.trim().toLowerCase(Locale.ROOT);
            scroll = 0;
        }, true, 40).bare().placeholder("Search actions or keys...");
        int at = Arrays.asList(LAYOUTS).indexOf(DuskConfig.get().keyboardLayout);
        layout = at < 0 ? 2 : at;
        caps = board(layout);
        KeyMapping[] all = Minecraft.getInstance().options.keyMappings.clone();
        Arrays.sort(all);
        for (KeyMapping k : all) actions.add(new Action(k));
        if (DuskClient.modules() != null) {
            for (Module.Category cat : Module.Category.values()) {
                for (Module m : DuskClient.modules().all()) if (m.category() == cat) actions.add(new Action(m));
            }
        }
    }

    // ---- the boards ---------------------------------------------------------

    /** Lays caps along a row, left to right, in key units. */
    private static final class Rows {
        final List<Cap> out = new ArrayList<>();
        double x, y;

        Rows row(double y) {
            this.y = y;
            this.x = 0;
            return this;
        }

        Rows at(double x) {
            this.x = x;
            return this;
        }

        Rows key(String label, int code, double w) { return key(label, code, w, 1); }

        Rows key(String label, int code, double w, double h) {
            out.add(new Cap(x, y, w, h, label, Input.KEYBOARD.getOrCreate(code)));
            x += w;
            return this;
        }

        Rows keys(String labels, int... codes) {
            for (int i = 0; i < codes.length; i++) key(String.valueOf(labels.charAt(i)), codes[i], 1);
            return this;
        }
    }

    private static final int[] NUMBERS = {InputConstants.KEY_GRAVE, InputConstants.KEY_1, InputConstants.KEY_2, InputConstants.KEY_3,
            InputConstants.KEY_4, InputConstants.KEY_5, InputConstants.KEY_6, InputConstants.KEY_7, InputConstants.KEY_8,
            InputConstants.KEY_9, InputConstants.KEY_0, InputConstants.KEY_MINUS, InputConstants.KEY_EQUALS};
    private static final int[] TOP = {InputConstants.KEY_Q, InputConstants.KEY_W, InputConstants.KEY_E, InputConstants.KEY_R,
            InputConstants.KEY_T, InputConstants.KEY_Y, InputConstants.KEY_U, InputConstants.KEY_I, InputConstants.KEY_O,
            InputConstants.KEY_P, InputConstants.KEY_LBRACKET, InputConstants.KEY_RBRACKET};
    private static final int[] HOME = {InputConstants.KEY_A, InputConstants.KEY_S, InputConstants.KEY_D, InputConstants.KEY_F,
            InputConstants.KEY_G, InputConstants.KEY_H, InputConstants.KEY_J, InputConstants.KEY_K, InputConstants.KEY_L,
            InputConstants.KEY_SEMICOLON, InputConstants.KEY_APOSTROPHE};
    private static final int[] BOTTOM = {InputConstants.KEY_Z, InputConstants.KEY_X, InputConstants.KEY_C, InputConstants.KEY_V,
            InputConstants.KEY_B, InputConstants.KEY_N, InputConstants.KEY_M, InputConstants.KEY_COMMA, InputConstants.KEY_PERIOD,
            InputConstants.KEY_SLASH};
    private static final int[] F = {InputConstants.KEY_F1, InputConstants.KEY_F2, InputConstants.KEY_F3, InputConstants.KEY_F4,
            InputConstants.KEY_F5, InputConstants.KEY_F6, InputConstants.KEY_F7, InputConstants.KEY_F8, InputConstants.KEY_F9,
            InputConstants.KEY_F10, InputConstants.KEY_F11, InputConstants.KEY_F12};

    /** The letter block's rows from {@code y}, the space row's right side left to {@code right}. */
    private static void alphas(Rows r, double y, boolean shortShift) {
        r.row(y).keys("`1234567890-=", NUMBERS).key("Backspace", InputConstants.KEY_BACKSPACE, 2);
        r.row(y + 1).key("Tab", InputConstants.KEY_TAB, 1.5).keys("QWERTYUIOP[]", TOP).key("\\", InputConstants.KEY_BACKSLASH, 1.5);
        r.row(y + 2).key("Caps", InputConstants.KEY_CAPSLOCK, 1.75).keys("ASDFGHJKL;'", HOME).key("Enter", InputConstants.KEY_RETURN, 2.25);
        r.row(y + 3).key("Shift", InputConstants.KEY_LSHIFT, 2.25).keys("ZXCVBNM,./", BOTTOM)
                .key("Shift", InputConstants.KEY_RSHIFT, shortShift ? 1.75 : 2.75);
        r.row(y + 4).key("Ctrl", InputConstants.KEY_LCONTROL, 1.25).key(MAC ? "Cmd" : "Win", Input.KEY_LSUPER, 1.25)
                .key(MAC ? "Opt" : "Alt", InputConstants.KEY_LALT, 1.25).key("", InputConstants.KEY_SPACE, 6.25);
    }

    private static List<Cap> board(int layout) {
        Rows r = new Rows();
        if (layout == 0) {
            alphas(r, 0, false);
            r.key(MAC ? "Opt" : "Alt", InputConstants.KEY_RALT, 1.25).key(MAC ? "Cmd" : "Win", Input.KEY_RSUPER, 1.25)
                    .key("Menu", Input.KEY_MENU, 1.25).key("Ctrl", InputConstants.KEY_RCONTROL, 1.25);
        } else if (layout == 1) {
            r.row(0).key("Esc", InputConstants.KEY_ESCAPE, 1);
            for (int i = 0; i < 12; i++) r.key("F" + (i + 1), F[i], 1);
            r.key("PrtSc", InputConstants.KEY_PRINTSCREEN, 1).key("Ins", InputConstants.KEY_INSERT, 1).key("Del", InputConstants.KEY_DELETE, 1);
            alphas(r, 1.25, true);
            r.key(MAC ? "Opt" : "Alt", InputConstants.KEY_RALT, 1).key("Menu", Input.KEY_MENU, 1).key("Ctrl", InputConstants.KEY_RCONTROL, 1)
                    .key("Left", InputConstants.KEY_LEFT, 1).key("Down", InputConstants.KEY_DOWN, 1).key("Right", InputConstants.KEY_RIGHT, 1);
            r.row(1.25).at(15).key("Home", InputConstants.KEY_HOME, 1);
            r.row(2.25).at(15).key("PgUp", InputConstants.KEY_PAGEUP, 1);
            r.row(3.25).at(15).key("PgDn", InputConstants.KEY_PAGEDOWN, 1);
            r.row(4.25).at(14).key("Up", InputConstants.KEY_UP, 1).key("End", InputConstants.KEY_END, 1);
        } else {
            r.row(0).key("Esc", InputConstants.KEY_ESCAPE, 1);
            for (int i = 0; i < 12; i++) {
                if (i % 4 == 0) r.at(2 + i / 4 * 4.5);
                r.key("F" + (i + 1), F[i], 1);
            }
            r.at(15.25).key("PrtSc", InputConstants.KEY_PRINTSCREEN, 1).key("ScrLk", InputConstants.KEY_SCROLLLOCK, 1)
                    .key("Pause", InputConstants.KEY_PAUSE, 1);
            alphas(r, 1.25, false);
            r.key(MAC ? "Opt" : "Alt", InputConstants.KEY_RALT, 1.25).key(MAC ? "Cmd" : "Win", Input.KEY_RSUPER, 1.25)
                    .key("Menu", Input.KEY_MENU, 1.25).key("Ctrl", InputConstants.KEY_RCONTROL, 1.25);
            r.at(15.25).key("Left", InputConstants.KEY_LEFT, 1).key("Down", InputConstants.KEY_DOWN, 1).key("Right", InputConstants.KEY_RIGHT, 1);
            r.at(18.5).key("0", InputConstants.KEY_NUMPAD0, 2).key(".", Input.KEY_NUM_DECIMAL, 1);
            r.row(1.25).at(15.25).key("Ins", InputConstants.KEY_INSERT, 1).key("Home", InputConstants.KEY_HOME, 1).key("PgUp", InputConstants.KEY_PAGEUP, 1);
            r.at(18.5).key("Num", InputConstants.KEY_NUMLOCK, 1).key("/", Input.KEY_NUM_DIVIDE, 1)
                    .key("*", InputConstants.KEY_MULTIPLY, 1).key("-", Input.KEY_NUM_SUBTRACT, 1);
            r.row(2.25).at(15.25).key("Del", InputConstants.KEY_DELETE, 1).key("End", InputConstants.KEY_END, 1).key("PgDn", InputConstants.KEY_PAGEDOWN, 1);
            r.at(18.5).key("7", InputConstants.KEY_NUMPAD7, 1).key("8", InputConstants.KEY_NUMPAD8, 1).key("9", InputConstants.KEY_NUMPAD9, 1)
                    .key("+", InputConstants.KEY_ADD, 1, 2);
            r.row(3.25).at(18.5).key("4", InputConstants.KEY_NUMPAD4, 1).key("5", InputConstants.KEY_NUMPAD5, 1).key("6", InputConstants.KEY_NUMPAD6, 1);
            r.row(4.25).at(16.25).key("Up", InputConstants.KEY_UP, 1);
            r.at(18.5).key("1", InputConstants.KEY_NUMPAD1, 1).key("2", InputConstants.KEY_NUMPAD2, 1).key("3", InputConstants.KEY_NUMPAD3, 1)
                    .key("Enter", InputConstants.KEY_NUMPADENTER, 1, 2);
        }
        // the mouse under the board, centred
        double w = units(layout), y = rowsHigh(layout) + 0.5, x = (w - 10) / 2;
        String[] names = {"Left", "Middle", "Right", "Mouse 4", "Mouse 5"};
        InputConstants.Type mouse = InputConstants.Type.MOUSE;
        InputConstants.Key[] buttons = {mouse.getOrCreate(InputConstants.MOUSE_BUTTON_LEFT), mouse.getOrCreate(InputConstants.MOUSE_BUTTON_MIDDLE),
                mouse.getOrCreate(InputConstants.MOUSE_BUTTON_RIGHT), Input.mouse(3), Input.mouse(4)};
        for (int i = 0; i < names.length; i++) r.out.add(new Cap(x + i * 2, y, 2, 1, names[i], buttons[i]));
        return r.out;
    }

    private static double units(int layout) { return layout == 0 ? 15 : layout == 1 ? 16 : 22.5; }

    private static double rowsHigh(int layout) { return layout == 0 ? 5 : 6.25; }

    /** What a cap says: the layout's own character for a printing key (AZERTY shows A where QWERTY has Q). */
    private static String capLabel(Cap cap) {
        if (cap.label.length() != 1 || cap.key.getType() != Input.KEYBOARD || cap.key.getName().contains("keypad")) return cap.label;
        String shown = cap.key.getDisplayName().getString();
        return shown.length() == 1 ? shown.toUpperCase(Locale.ROOT) : cap.label;
    }

    // ---- data ---------------------------------------------------------------

    private void index() {
        byKey.clear();
        for (Action a : actions) {
            String id = a.keyId();
            if (!id.isEmpty()) byKey.computeIfAbsent(id, k -> new ArrayList<>()).add(a);
        }
    }

    private List<Action> on(InputConstants.Key key) {
        return byKey.getOrDefault(key.getName(), List.of());
    }

    private boolean matches(Action a) {
        if (query.isEmpty()) return true;
        String hay = (a.name + " " + a.category + " " + a.keyLabel()).toLowerCase(Locale.ROOT);
        for (String word : query.split("\\s+")) if (!hay.contains(word)) return false;
        return true;
    }

    /** Lit by the search: its label is the query, or something on it matches. */
    private boolean found(Cap cap) {
        if (query.isEmpty()) return true;
        if (capLabel(cap).toLowerCase(Locale.ROOT).equals(query)
                || cap.key.getDisplayName().getString().toLowerCase(Locale.ROOT).equals(query)) return true;
        for (Action a : on(cap.key)) if (matches(a)) return true;
        return false;
    }

    private static String keyName(InputConstants.Key key) {
        return key.getDisplayName().getString();
    }

    private void bindListening(InputConstants.Key key) {
        Action a = listening;
        listening = null;
        if (a == null) return;
        if (!a.canHold(key)) {
            notice = "A module's toggle key can't be a mouse button.";
            return;
        }
        a.bind(key);
        notice = a.name + " is on " + keyName(key) + " now.";
    }

    // ---- layout -------------------------------------------------------------

    @Override
    protected MenuFrame.Entry frameEntry() {
        return MenuFrame.Entry.KEYBINDS;
    }

    @Override
    protected String[] tabs() {
        return TABS;
    }

    @Override
    protected int activeTab() {
        return layout;
    }

    @Override
    protected void selectTab(int i) {
        layout = i;
        caps = board(i);
        DuskConfig.get().keyboardLayout = LAYOUTS[i];
        DuskConfig.save();
    }

    @Override
    protected List<Tool> tools() {
        return List.of(new Tool("close", Icons.CLOSE, "", "Close"), new Tool("list", null, "LIST", "Dusk's keys as a list"));
    }

    @Override
    protected void onTool(String id) {
        if (id.equals("list")) MenuFrame.go(this, null, MenuFrame.Entry.KEYBINDS);
        else super.onTool(id);
    }

    @Override
    protected TextFieldWidget search() {
        return search;
    }

    private void layout() {
        layoutPanel();
        listX = px + pad;
        listY = bodyY() + pad;
        listW = px + pw - pad - BAR_W - 4 - listX;
        listBottom = py + ph - pad;
        index();
        double units = units(layout), high = rowsHigh(layout) + 1.5;
        unit = Math.max(10, Math.min(28, (int) Math.floor(listW / units)));
        boardW = (int) Math.round(units * unit);
        boardH = (int) Math.round(high * unit);
        boardX = listX + (listW - boardW) / 2;
        lowerY = boardH + LEGEND_H + 10;
        bound.clear();
        others.clear();
        for (Action a : actions) {
            boolean on = selected != null && a.keyId().equals(selected.getName());
            if (on) bound.add(a);
            else if (matches(a)) others.add(a);
        }
        clampScroll();
        boardY = listY - scroll;
    }

    @Override
    protected int contentHeight() {
        int rows = selected == null ? Math.max(1, others.size()) : bound.size() + Math.max(1, others.size());
        int heads = selected == null ? 1 : 2;
        return lowerY + heads * HEAD_H + rows * (ROW_H + ROW_GAP);
    }

    private int capX(Cap cap) { return boardX + (int) Math.round(cap.x * unit); }

    private int capY(Cap cap) { return boardY + (int) Math.round(cap.y * unit); }

    private int capW(Cap cap) { return (int) Math.round((cap.x + cap.w) * unit) - (int) Math.round(cap.x * unit); }

    private int capH(Cap cap) { return (int) Math.round((cap.y + cap.h) * unit) - (int) Math.round(cap.y * unit); }

    // ---- drawing ------------------------------------------------------------

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        swallowChar = false;
        layout();
        drawPanel(c, mouseX, mouseY);
        hits.clear();
        boolean hotList = inList(mouseX, mouseY);
        int mx = hotList ? mouseX : -1, my = hotList ? mouseY : -1;
        c.scissor(listX, listY, listX + listW, listBottom);
        Cap hovered = drawBoard(c, mx, my);
        drawLegend(c);
        drawLower(c, mx, my);
        c.unscissor();
        drawScrollbar(c);
        if (hovered != null && listening == null) {
            c.beginLayer();
            capTip(c, hovered, mouseX, mouseY);
            c.endLayer();
        }
    }

    @Nullable
    private Cap drawBoard(Canvas c, int mx, int my) {
        Cap hovered = null;
        int r = Math.max(2, Round.w(unit * 0.18));
        int gap = unit >= 16 ? 2 : 1;
        for (Cap cap : caps) {
            int x = capX(cap) + gap / 2, y = capY(cap) + gap / 2, w = capW(cap) - gap, h = capH(cap) - gap;
            if (y + h < listY || y > listBottom) continue;
            boolean hot = Vanilla.inside(mx, my, x, y, w, h);
            if (hot) hovered = cap;
            hits.add(new Hit("cap", null, x, y, w, h));
            List<Action> here = on(cap.key);
            boolean picked = selected != null && selected.equals(cap.key);
            boolean lit = found(cap);
            int ring = here.isEmpty() ? Theme.LINE_3 : here.size() == 1 ? Theme.OK : Theme.BAD;
            if (picked) ring = Theme.ACCENT;
            if (!lit) ring = Theme.LINE_2;
            int fill = picked ? 0xFF34343B : hot ? 0xFF2E2E34 : 0xFF222226;
            if (!lit) fill = 0xFF19191C;
            if (Theme.modern()) {
                Round.fill(c, x, y, w, h, r, fill, ring);
                if (picked) Round.ring(c, x - 1, y - 1, w + 2, h + 2, r + 2, Theme.ACCENT);
            } else {
                c.fill(x, y, x + w, y + h, fill);
                c.outline(x, y, w, h, ring);
                if (picked) c.outline(x - 1, y - 1, w + 2, h + 2, Theme.ACCENT);
            }
            int text = !lit ? Theme.TEXT_FAINT : here.isEmpty() ? Theme.TEXT_MUTED : Theme.TEXT;
            Px.sans(c, capLabel(cap), x, y, w, h, unit >= 20 ? Px.Size.S14 : Px.Size.S11, text, false, false);
        }
        return hovered;
    }

    private void drawLegend(Canvas c) {
        int y = boardY + boardH + 2;
        if (y + LEGEND_H < listY || y > listBottom) return;
        int x = listX + 2;
        x = legendItem(c, x, y, Theme.OK, "One action");
        x = legendItem(c, x, y, Theme.BAD, "Conflict");
        legendItem(c, x, y, Theme.LINE_3, "Unused");
        String hint = listening != null ? "Press a key, or click one, for " + listening.name + ". Esc cancels."
                : selected != null ? "Click the key again, or another, to change what you're looking at."
                : "Click a key to see and change what it does.";
        if (notice != null && listening == null) hint = notice;
        int right = listX + listW - 2;
        int room = right - x - 90;
        String shown = Theme.ellipsize(c, hint, Math.max(40, room));
        c.text(shown, right - c.textWidth(shown), y + 3, listening != null ? Theme.ACCENT : Theme.TEXT_MUTED, false);
    }

    private int legendItem(Canvas c, int x, int y, int color, String label) {
        if (Theme.modern()) Round.fill(c, x, y + 2, 10, 10, Round.w(2.5), 0xFF222226, color);
        else {
            c.fill(x, y + 2, x + 10, y + 12, 0xFF222226);
            c.outline(x, y + 2, 10, 10, color);
        }
        c.text(label, x + 14, y + 3, Theme.TEXT_MUTED, false);
        return x + 14 + c.textWidth(label) + 12;
    }

    private void drawLower(Canvas c, int mx, int my) {
        int y = boardY + lowerY;
        if (selected != null) {
            String title = keyName(selected) + (bound.isEmpty() ? "  ·  nothing on it" : "  ·  " + bound.size()
                    + (bound.size() == 1 ? " action" : " actions" + "  ·  conflict"));
            heading(c, title, y, mx, my, "DONE");
            y += HEAD_H;
            for (Action a : bound) {
                drawRow(c, a, y, mx, my, true);
                y += ROW_H + ROW_GAP;
            }
            heading(c, "Put another action on " + keyName(selected), y, mx, my, null);
            y += HEAD_H;
        } else {
            heading(c, (query.isEmpty() ? "All actions" : "Matching actions") + "  ·  " + others.size(), y, mx, my, null);
            y += HEAD_H;
        }
        if (others.isEmpty()) {
            c.text("Nothing matches \"" + query + "\".", listX + 4, y + 8, Theme.TEXT_MUTED, false);
            return;
        }
        for (Action a : others) {
            if (y > listBottom) break;
            if (y + ROW_H >= listY) drawRow(c, a, y, mx, my, false);
            y += ROW_H + ROW_GAP;
        }
    }

    private void heading(Canvas c, String text, int y, int mx, int my, @Nullable String button) {
        if (y + HEAD_H < listY || y > listBottom) return;
        int right = listX + listW;
        if (button != null) {
            int bx = right - BTN_W, by = y + (HEAD_H - BTN_H) / 2 - 1;
            boxButton(c, button, bx, by, BTN_W, BTN_H, mx, my, Theme.Family.GREY, false, true);
            hits.add(new Hit("done", null, bx, by, BTN_W, BTN_H));
            right = bx - 6;
        }
        c.text(Theme.ellipsize(c, text, right - listX - 4), listX + 2, y + (HEAD_H - 8) / 2, Theme.TEXT, false);
    }

    /** {@code onKey}: on the picked key (UNBIND and RESET), else a candidate (its key, then ASSIGN or RESET). */
    private void drawRow(Canvas c, Action a, int y, int mx, int my, boolean onKey) {
        Theme.plate(c, listX, y, listW, ROW_H, Theme.SURFACE, Theme.SURFACE, false);
        int right = listX + listW - 6, by = y + (ROW_H - BTN_H) / 2;
        if (onKey) {
            right = button(c, "UNBIND", "unbind", a, right, by, mx, my, true);
            if (!a.isDefault()) right = button(c, "RESET", "reset", a, right, by, mx, my, true);
        } else {
            if (selected != null) right = button(c, "ASSIGN", "assign", a, right, by, mx, my, a.canHold(selected));
            else if (!a.isDefault()) right = button(c, "RESET", "reset", a, right, by, mx, my, true);
            right = pill(c, a, right, by, mx, my, selected == null);
        }
        int tw = right - 8 - (listX + 8);
        int count = a.keyId().isEmpty() ? 0 : byKey.getOrDefault(a.keyId(), List.of()).size();
        c.text(Theme.ellipsize(c, a.name, tw), listX + 8, y + 5, Theme.TEXT, false);
        String sub = a.category + (count > 1 ? "  ·  shares its key with " + (count - 1) + " more" : "");
        c.text(Theme.ellipsize(c, sub, tw), listX + 8, y + 16, count > 1 ? Theme.BAD : Theme.TEXT_FAINT, false);
    }

    private int button(Canvas c, String label, String what, Action a, int right, int by, int mx, int my, boolean enabled) {
        int bx = right - BTN_W;
        boxButton(c, label, bx, by, BTN_W, BTN_H, mx, my, what.equals("assign") ? Theme.Family.ACCENT : Theme.Family.GREY, false, enabled);
        if (enabled) hits.add(new Hit(what, a, bx, by, BTN_W, BTN_H));
        return bx - 4;
    }

    /** The action's key; with no key picked, clicking it listens for a new one. */
    private int pill(Canvas c, Action a, int right, int by, int mx, int my, boolean clickable) {
        int bx = right - PILL_W;
        boolean wait = a == listening;
        boolean hot = clickable && Vanilla.inside(mx, my, bx, by, PILL_W, BTN_H);
        String label = wait ? "Press a key" : a.keyLabel();
        int text = wait ? Theme.ACCENT : a.keyId().isEmpty() ? Theme.TEXT_FAINT : hot ? Theme.TEXT : Theme.TEXT_MUTED;
        if (Theme.modern()) {
            Px.box(c, bx, by, PILL_W, BTN_H, wait ? Theme.Family.ACCENT : Theme.Family.GREY, hot, false, 0xFF);
            Px.sans(c, label, bx, by, PILL_W, BTN_H, Px.Size.S14, wait ? Theme.INK : text, false, false);
        } else {
            Px.button(c, bx, by, PILL_W, BTN_H, hot, true);
            Px.sans(c, label, bx, by, PILL_W, BTN_H, Px.Size.S14, text, false, false);
        }
        if (clickable) hits.add(new Hit("listen", a, bx, by, PILL_W, BTN_H));
        return bx - 4;
    }

    private void capTip(Canvas c, Cap cap, int mx, int my) {
        List<String> lines = new ArrayList<>();
        lines.add(keyName(cap.key));
        List<Action> here = on(cap.key);
        if (here.isEmpty()) lines.add("Nothing on this key");
        for (int i = 0; i < Math.min(8, here.size()); i++) lines.add(here.get(i).name + "  (" + here.get(i).category + ")");
        if (here.size() > 8) lines.add("and " + (here.size() - 8) + " more");
        lines.add(here.size() > 1 ? "These all fire together. Click to sort it out" : "Click to change it");
        int w = 0;
        for (String l : lines) w = Math.max(w, c.textWidth(l));
        int padX = Theme.modern() ? 6 : 4, padY = Theme.modern() ? 5 : 3;
        w += 2 * padX;
        int h = lines.size() * 10 + 2 * padY - 2;
        int x = Math.min(mx + 10, this.width - w - 2), y = Math.max(2, Math.min(my - 14, this.height - h - 2));
        Vanilla.popBox(c, x, y, w, h);
        for (int i = 0; i < lines.size(); i++) {
            int col = i == 0 ? Theme.TEXT : i == lines.size() - 1 ? Theme.TEXT_FAINT
                    : here.size() > 1 ? Theme.BAD : Theme.TEXT_MUTED;
            c.text(lines.get(i), x + padX, y + padY + i * 10, col, !Theme.modern());
        }
    }

    // ---- input --------------------------------------------------------------

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        layout();
        if (listening != null && button != 0 && inList(mx, my)) {
            // any button but the left binds straight away; the left still picks from the board
            bindListening(Input.mouse(button));
            return true;
        }
        if (clickChrome(mx, my, button)) {
            listening = null;
            return true;
        }
        if (!inList(mx, my)) return Vanilla.inside(mx, my, px, py, pw, ph);
        notice = null;
        for (Cap cap : caps) {
            int x = capX(cap), y = capY(cap);
            if (!Vanilla.inside(mx, my, x, y, capW(cap), capH(cap))) continue;
            if (button != 0) {
                // right (or middle) click: clear everything on the key
                for (Action a : new ArrayList<>(on(cap.key))) a.unbind();
                return true;
            }
            if (listening != null) {
                bindListening(cap.key);
                return true;
            }
            selected = cap.key.equals(selected) ? null : cap.key;
            scroll = Math.min(scroll, Math.max(0, boardH - 20));
            return true;
        }
        for (Hit h : hits) {
            if (!h.in(mx, my) || h.what.equals("cap")) continue;
            Action a = h.action;
            switch (h.what) {
                case "done" -> selected = null;
                case "unbind" -> a.unbind();
                case "reset" -> a.reset();
                case "assign" -> {
                    if (selected != null) {
                        a.bind(selected);
                        notice = a.name + " is on " + keyName(selected) + " now.";
                    }
                }
                case "listen" -> listening = a == listening ? null : a;
                default -> {}
            }
            return true;
        }
        listening = null;
        return true;
    }

    @Override
    protected boolean menuKey(int key, int scancode, int modifiers) {
        if (listening != null) {
            if (key == InputConstants.KEY_ESCAPE) listening = null;
            else bindListening(Input.KEYBOARD.getOrCreate(key));
            swallowChar = true;
            return true;
        }
        if (super.menuKey(key, scancode, modifiers)) return true;
        if (key == InputConstants.KEY_ESCAPE && selected != null) {
            selected = null;
            return true;
        }
        return false;
    }

    @Override
    protected boolean menuChar(char ch) {
        if (swallowChar || listening != null) return true;
        return super.menuChar(ch);
    }
}
