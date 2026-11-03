package dev.dusk.client.modules.misc;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.enchant.EnchantPredictor;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.MenuScreen;
import dev.dusk.client.gui.Px;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.gui.Vanilla;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link EnchantOracle}'s goal: every enchantment the table can roll on the
 * item, each clicked up to the lowest level wanted (right-click goes down).
 * Typing filters the list.
 */
final class OracleGoalScreen extends MenuScreen {
    private static final int W = 260, ROW = Px.H + 2, BUTTON_W = 80;

    private final EnchantOracle oracle;
    private final ItemStack item;
    private final List<Holder<Enchantment>> all;
    /** Picked level per enchantment; 0 is not wanted. */
    private final Map<Holder<Enchantment>, Integer> levels = new LinkedHashMap<>();
    private String filter = "";
    private int scroll;

    OracleGoalScreen(Screen parent, EnchantOracle oracle, ItemStack item) {
        super(Component.literal("Enchant goal"), parent);
        this.oracle = oracle;
        this.item = item.copyWithCount(1);
        EnchantPredictor p = oracle.predictor();
        this.all = p == null || item.isEmpty() ? List.of() : p.rollable(item);
        for (Holder<Enchantment> h : all) levels.put(h, 0);
        if (!oracle.target().isEmpty() && oracle.target().is(item.getItem())) {
            for (EnchantOracle.Want w : oracle.wants()) levels.computeIfPresent(w.enchantment(), (h, l) -> w.min());
        }
    }

    private List<Holder<Enchantment>> shown() {
        String f = filter.toLowerCase(Locale.ROOT);
        List<Holder<Enchantment>> out = new ArrayList<>();
        for (Holder<Enchantment> h : all) {
            if (f.isEmpty() || name(h).toLowerCase(Locale.ROOT).contains(f)) out.add(h);
        }
        return out;
    }

    private static String name(Holder<Enchantment> h) {
        return h.value().description().getString();
    }

    private int left() { return (this.width - W) / 2; }
    private int listTop() { return 44; }
    private int listBottom() { return this.height - Px.H - 30; }
    private int visibleRows() { return Math.max(1, (listBottom() - listTop()) / ROW); }
    private int buttonsY() { return this.height - Px.H - 10; }

    /** The first two picked enchantments that can't go on one item, as a warning, or empty. */
    private String clash() {
        List<Holder<Enchantment>> picked = new ArrayList<>();
        levels.forEach((h, l) -> {
            if (l > 0) picked.add(h);
        });
        for (int i = 0; i < picked.size(); i++) {
            for (int j = i + 1; j < picked.size(); j++) {
                if (!Enchantment.areCompatible(picked.get(i), picked.get(j))) {
                    return name(picked.get(i)) + " and " + name(picked.get(j)) + " can't go on one item.";
                }
            }
        }
        return "";
    }

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        int x = left();
        Px.label(c, "Goal for " + item.getHoverName().getString(), 0, 8, this.width, Px.H, Px.Size.S16, Px.Tone.ACTIVE,
                false, false, false, 0xFF);
        if (all.isEmpty()) {
            c.centeredText("Put the item you want to enchant in the table first.", this.width / 2, this.height / 2, Theme.TEXT_MUTED);
        } else {
            String hint = filter.isEmpty() ? "Click to raise a level, right-click to lower. Type to filter." : "Filter: " + filter;
            c.centeredText(hint, this.width / 2, 30, Theme.TEXT_MUTED);
        }

        List<Holder<Enchantment>> rows = shown();
        scroll = Math.max(0, Math.min(scroll, rows.size() - visibleRows()));
        c.scissor(x, listTop(), x + W, listBottom());
        for (int i = 0; i < visibleRows() && scroll + i < rows.size(); i++) {
            Holder<Enchantment> h = rows.get(scroll + i);
            int y = listTop() + i * ROW;
            int level = levels.get(h);
            boolean hot = Vanilla.inside(mouseX, mouseY, x, y, W, Px.H);
            Px.box(c, x, y, W, Px.H, level > 0 ? Theme.Family.ACCENT : Theme.Family.GREY, hot, false, 0xFF);
            int ty = y + (Px.H - c.lineHeight()) / 2 + 1;
            c.text(name(h), x + 8, ty, level > 0 ? 0xFF1E1E1E : Theme.TEXT, level == 0);
            int max = h.value().getMaxLevel();
            String right = level == 0 ? "-" : max == 1 ? "wanted"
                    : Component.translatable("enchantment.level." + level).getString() + (level < max ? " or better" : "");
            c.text(right, x + W - 8 - c.textWidth(right), ty, level > 0 ? 0xFF1E1E1E : Theme.TEXT_MUTED, level == 0);
        }
        c.unscissor();

        String warn = clash();
        if (!warn.isEmpty()) c.centeredText(warn, this.width / 2, buttonsY() - 12, 0xFFFF5555);

        int y = buttonsY(), cx = this.width / 2;
        boolean clearHot = Vanilla.inside(mouseX, mouseY, cx - BUTTON_W - 4, y, BUTTON_W, Px.H);
        boolean doneHot = Vanilla.inside(mouseX, mouseY, cx + 4, y, BUTTON_W, Px.H);
        Px.box(c, cx - BUTTON_W - 4, y, BUTTON_W, Px.H, Theme.Family.GREY, clearHot, false, 0xFF);
        Px.label(c, "Clear", cx - BUTTON_W - 4, y, BUTTON_W, Px.H, Px.Size.S16, Px.Tone.GREY, clearHot, false, false, 0xFF);
        Px.box(c, cx + 4, y, BUTTON_W, Px.H, Theme.Family.ACCENT, doneHot, false, 0xFF);
        Px.label(c, "Done", cx + 4, y, BUTTON_W, Px.H, Px.Size.S16, Px.Tone.ACTIVE, doneHot, false, false, 0xFF);
    }

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        int x = left(), y = buttonsY(), cx = this.width / 2;
        if (button == 0 && Vanilla.inside(mx, my, cx - BUTTON_W - 4, y, BUTTON_W, Px.H)) {
            levels.replaceAll((h, l) -> 0);
            return true;
        }
        if (button == 0 && Vanilla.inside(mx, my, cx + 4, y, BUTTON_W, Px.H)) {
            onClose();
            return true;
        }
        if (mx < x || mx >= x + W || my < listTop() || my >= listBottom()) return false;
        int i = (int) ((my - listTop()) / ROW);
        List<Holder<Enchantment>> rows = shown();
        if (scroll + i >= rows.size() || my - listTop() - i * ROW >= Px.H) return false;
        Holder<Enchantment> h = rows.get(scroll + i);
        int max = h.value().getMaxLevel(), level = levels.get(h);
        levels.put(h, button == 1 ? (level + max) % (max + 1) : (level + 1) % (max + 1));
        return true;
    }

    @Override
    protected boolean menuScroll(double mx, double my, double amount) {
        scroll -= (int) Math.signum(amount);
        return true;
    }

    @Override
    protected boolean menuKey(int key, int scancode, int modifiers) {
        if (key == InputConstants.KEY_BACKSPACE && !filter.isEmpty()) {
            filter = filter.substring(0, filter.length() - 1);
            scroll = 0;
            return true;
        }
        return false;
    }

    @Override
    protected boolean menuChar(char ch) {
        if (!Character.isLetter(ch) && ch != ' ') return false;
        filter += ch;
        scroll = 0;
        return true;
    }

    @Override
    protected void beforeClose() {
        List<EnchantOracle.Want> picked = new ArrayList<>();
        levels.forEach((h, l) -> {
            if (l > 0) picked.add(new EnchantOracle.Want(h, l));
        });
        if (picked.isEmpty()) oracle.clearGoal();
        else oracle.setGoal(item, picked);
        super.beforeClose();
    }
}
