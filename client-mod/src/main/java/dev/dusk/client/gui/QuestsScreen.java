package dev.dusk.client.gui;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.social.Social;
import dev.dusk.client.social.SocialNotifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Quests, like the launcher's page: the daily streak, today's and this
 * week's boards, and achievements (the badges on your profile). Play time
 * comes from the game's own ticks (see {@link SocialNotifier}); this only
 * reads the boards and claims what's done.
 */
public class QuestsScreen extends PanelScreen {
    private static final String[] TABS = {"QUESTS", "ACHIEVEMENTS"};
    private static final int QUESTS = 0, ACHIEVEMENTS = 1;
    private static final int ROW_H = 30, GAP = 4, HEAD_H = 16, STREAK_H = 46, BTN_W = 56, BTN_H = 16;

    private enum Kind { HEAD, STREAK, QUEST }

    /** One laid-out piece of the list; {@code text} is a header's, {@code quest} a row's. */
    private record Item(Kind kind, int y, int h, @Nullable String text, @Nullable Social.Quest quest) {}

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "duskclient-quests");
        t.setDaemon(true);
        return t;
    });

    // render thread only
    private int tab;
    @Nullable private Social.Quests quests;
    private long readAt;
    @Nullable private String error, status;
    private boolean statusError, busy;
    private List<Item> items = List.of();
    private int height;

    public QuestsScreen(@Nullable Screen parent) {
        super(Component.literal("Quests"), parent);
        reload();
    }

    public static void show(@Nullable Screen parent) {
        Compat.setScreen(Minecraft.getInstance(), new QuestsScreen(parent));
    }

    private void post(Runnable r) {
        Minecraft.getInstance().execute(r);
    }

    private void reload() {
        worker.execute(() -> {
            try {
                Social.Quests q = Social.quests();
                post(() -> take(q));
            } catch (IOException | RuntimeException e) {
                post(() -> error = e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    private void take(Social.Quests q) {
        quests = q;
        readAt = System.currentTimeMillis();
        error = null;
        SocialNotifier.ready(q.claimable());
    }

    private void claim(String id) {
        if (busy) return;
        busy = true;
        status = "Claiming...";
        statusError = false;
        worker.execute(() -> {
            try {
                Social.Claimed c = Social.claim(id);
                post(() -> {
                    busy = false;
                    take(c.quests());
                    status = c.paid() > 0 ? "+" + c.paid() + " coins" : "Nothing to claim yet.";
                });
            } catch (IOException | RuntimeException e) {
                post(() -> {
                    busy = false;
                    status = e.getMessage() == null ? e.toString() : e.getMessage();
                    statusError = true;
                });
            }
        });
    }

    // ---- layout -------------------------------------------------------------

    @Override
    protected String[] tabs() {
        return TABS;
    }

    @Override
    protected int activeTab() {
        return tab;
    }

    @Override
    protected void selectTab(int i) {
        tab = i;
        scroll = 0;
    }

    @Override
    protected List<Tool> tools() {
        List<Tool> t = new ArrayList<>();
        t.add(new Tool("close", Icons.CLOSE, "", "Close"));
        if (quests != null && quests.claimable() > 0) {
            t.add(new Tool("all", null, "CLAIM ALL (" + quests.claimable() + ")", "Claim every finished quest"));
        }
        return t;
    }

    @Override
    protected void onTool(String id) {
        if (id.equals("all")) claim("all");
        else super.onTool(id);
    }

    private void layout() {
        layoutPanel();
        listX = px + pad;
        listY = bodyY() + pad + 14;
        listW = px + pw - pad - BAR_W - 4 - listX;
        listBottom = py + ph - pad;
        List<Item> out = new ArrayList<>();
        int y = 0;
        Social.Quests q = quests;
        if (q != null) {
            if (tab == QUESTS) {
                out.add(new Item(Kind.STREAK, y, STREAK_H, null, null));
                y += STREAK_H + GAP + 4;
                y = section(out, y, "DAILY  ·  new in " + until(q.dailyReset()), q.daily());
                y += 4;
                y = section(out, y, "WEEKLY  ·  new in " + until(q.weeklyReset()), q.weekly());
            } else {
                y = section(out, y, "Achievements stay on your profile as badges.", q.achievements());
            }
        }
        items = out;
        height = Math.max(0, y - GAP);
        clampScroll();
    }

    private static int section(List<Item> out, int y, String head, List<Social.Quest> list) {
        out.add(new Item(Kind.HEAD, y, HEAD_H, head, null));
        y += HEAD_H;
        for (Social.Quest q : list) {
            out.add(new Item(Kind.QUEST, y, ROW_H, null, q));
            y += ROW_H + GAP;
        }
        return y;
    }

    @Override
    protected int contentHeight() {
        return height;
    }

    /** Time left of a reset {@code s} seconds after the boards were read. */
    private String until(long s) {
        long left = Math.max(0, s - (System.currentTimeMillis() - readAt) / 1000);
        long d = left / 86400, h = left % 86400 / 3600, m = left % 3600 / 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return Math.max(1, m) + "m";
    }

    private static String amount(long n, String unit) {
        if (!unit.equals("min")) return Long.toString(n);
        if (n < 60) return n + "m";
        return n % 60 == 0 ? n / 60 + "h" : n / 60 + "h " + n % 60 + "m";
    }

    private int buttonX() {
        return listX + listW - BTN_W - 6;
    }

    // ---- drawing ------------------------------------------------------------

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        layout();
        drawPanel(c, mouseX, mouseY);
        int top = bodyY() + pad;
        Social.Quests q = quests;
        if (q != null) {
            String coins = q.coins() + " coins";
            Icons.STAR.draw(c, listX, top + 1, Theme.ACCENT);
            c.text(coins, listX + 11, top, Theme.ACCENT, false);
            if (status != null) {
                int sx = listX + 11 + c.textWidth(coins) + 10;
                c.text(Theme.ellipsize(c, status, listX + listW - sx), sx, top, statusError ? Theme.RED_UP : Theme.TEXT_MUTED, false);
            }
        }
        if (q == null) {
            String msg = error != null ? "Couldn't load quests: " + error : "Loading quests...";
            c.text(Theme.ellipsize(c, msg, listW - 8), listX + 4, listY + 4, error != null ? Theme.RED_UP : Theme.TEXT_MUTED, false);
            return;
        }
        boolean hot = inList(mouseX, mouseY);
        int mx = hot ? mouseX : -1, my = hot ? mouseY : -1;
        c.scissor(listX, listY, listX + listW, listBottom);
        for (Item it : items) {
            int y = listY + it.y() - scroll;
            if (y + it.h() < listY || y > listBottom) continue;
            switch (it.kind()) {
                case HEAD -> c.text(Theme.ellipsize(c, it.text(), listW), listX + 2, y + 4, Theme.TEXT_FAINT, false);
                case STREAK -> drawStreak(c, q.streak(), y, mx, my);
                case QUEST -> drawQuest(c, it.quest(), y, mx, my);
            }
        }
        c.unscissor();
        drawScrollbar(c);
    }

    private void drawStreak(Canvas c, Social.Streak s, int y, int mx, int my) {
        Theme.plate(c, listX, y, listW, STREAK_H, Theme.SURFACE, Theme.SURFACE_BOT, false);
        int tx = listX + 8;
        String title = "Daily streak  ·  " + s.days() + (s.days() == 1 ? " day" : " days");
        c.text(title, tx, y + 6, Theme.TEXT, false);
        String sub = s.done() ? "Today counts. Come back tomorrow to keep it going."
                : "Play " + s.needMinutes() + " minutes today: " + Math.min(s.todayMinutes(), s.needMinutes()) + " / " + s.needMinutes();
        int textW = buttonX() - 8 - tx;
        c.text(Theme.ellipsize(c, sub, textW), tx, y + 17, Theme.TEXT_MUTED, false);
        // the week's payouts, today's lit
        int cell = Math.max(18, Math.min(30, (textW - 6 * 2) / 7));
        for (int i = 0; i < s.cycle().size(); i++) {
            int cx = tx + i * (cell + 2), cy = y + 29;
            boolean today = i == s.cycleDay(), past = i < s.cycleDay();
            int fill = today ? (s.done() ? 0xFF2E5A1E : 0xFF4A3A12) : past ? 0xFF243A1C : 0xFF262626;
            c.fill(cx, cy, cx + cell, cy + 11, fill);
            String v = Long.toString(s.cycle().get(i));
            int col = today ? Theme.ACCENT : past ? Theme.GREEN_LO : Theme.TEXT_FAINT;
            c.text(v, cx + (cell - c.textWidth(v)) / 2, cy + 2, col, false);
        }
        int by = y + (STREAK_H - BTN_H) / 2;
        claimButton(c, s.done(), s.claimed(), "+" + s.coins(), by, mx, my);
    }

    private void drawQuest(Canvas c, Social.Quest q, int y, int mx, int my) {
        Theme.plate(c, listX, y, listW, ROW_H, Theme.SURFACE, Theme.SURFACE, false);
        int tx = listX + 8, coinsW = 44, textW = buttonX() - coinsW - 8 - tx;
        c.text(Theme.ellipsize(c, q.title(), textW), tx, y + 5, q.claimed() ? Theme.TEXT_MUTED : Theme.TEXT, false);
        // progress bar with the count beside it
        String count = amount(Math.min(q.progress(), q.goal()), q.unit()) + " / " + amount(q.goal(), q.unit());
        int barW = Math.max(30, textW - c.textWidth(count) - 8), barY = y + 19;
        c.fill(tx, barY, tx + barW, barY + 4, 0xFF141414);
        int done = (int) (barW * Math.min(1.0, q.goal() <= 0 ? 1.0 : q.progress() / (double) q.goal()));
        c.fill(tx, barY, tx + done, barY + 4, q.done() ? Theme.GREEN_LO : Theme.ACCENT);
        c.text(count, tx + barW + 6, y + 17, Theme.TEXT_FAINT, false);
        String coins = "+" + q.coins();
        int cx = buttonX() - 8 - c.textWidth(coins);
        c.text(coins, cx, y + (ROW_H - 8) / 2, q.claimed() ? Theme.TEXT_FAINT : Theme.ACCENT, false);
        claimButton(c, q.done(), q.claimed(), null, y + (ROW_H - BTN_H) / 2, mx, my);
    }

    private void claimButton(Canvas c, boolean done, boolean claimed, @Nullable String pending, int y, int mx, int my) {
        int x = buttonX();
        if (claimed) {
            boxButton(c, "CLAIMED", x, y, BTN_W, BTN_H, mx, my, Theme.Family.GREY, true, false);
        } else if (done) {
            boxButton(c, "CLAIM", x, y, BTN_W, BTN_H, mx, my, Theme.Family.ACCENT, false, !busy);
        } else {
            String label = pending != null ? pending : "...";
            c.text(label, x + (BTN_W - c.textWidth(label)) / 2, y + (BTN_H - 7) / 2, Theme.TEXT_FAINT, false);
        }
    }

    // ---- input --------------------------------------------------------------

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        layout();
        if (clickChrome(mx, my, button)) return true;
        if (!inList(mx, my)) return Vanilla.inside(mx, my, px, py, pw, ph);
        Social.Quests q = quests;
        if (q == null || button != 0) return true;
        for (Item it : items) {
            int y = listY + it.y() - scroll;
            if (my < y || my >= y + it.h()) continue;
            int by = y + (it.h() - BTN_H) / 2;
            if (!Vanilla.inside(mx, my, buttonX(), by, BTN_W, BTN_H)) return true;
            if (it.kind() == Kind.STREAK && q.streak().done() && !q.streak().claimed()) claim("streak");
            if (it.kind() == Kind.QUEST && it.quest().done() && !it.quest().claimed()) claim(it.quest().id());
            return true;
        }
        return true;
    }

    @Override
    public void removed() {
        super.removed();
        worker.shutdown();
    }
}
