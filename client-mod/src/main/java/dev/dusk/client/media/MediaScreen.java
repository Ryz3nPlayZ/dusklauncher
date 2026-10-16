package dev.dusk.client.media;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.DuskClient;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Icons;
import dev.dusk.client.gui.MenuFrame;
import dev.dusk.client.gui.PanelScreen;
import dev.dusk.client.gui.RemoteImages;
import dev.dusk.client.gui.Round;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.gui.Vanilla;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * MEDIA: this instance's screenshots, screen recordings, saved clips and
 * full replays as thumbnail grids, and the recording settings. Screenshots
 * open full size here (← / → step through); videos open in the system's
 * player; clips and replays play in the replay viewer.
 */
public class MediaScreen extends PanelScreen {
    public static final int SCREENSHOTS = 0, VIDEOS = 1, CLIPS = 2, REPLAYS = 3, SETTINGS = 4;
    private static final String[] TABS = {"SCREENSHOTS", "VIDEOS", "CLIPS", "REPLAYS", "SETTINGS"};
    private static final int GAP = 6, MIN_TILE = 132, INFO_H = 22, BTN_H = 16, DEL_W = 42, ROW_H = 34, SECTION_H = 18;
    private static final int[] CLIP_LENGTHS = {15, 30, 60, 90, 120, 300};
    private static final int[] VIDEO_LENGTHS = {15, 30, 60, 120, 300};
    private static final String[][] MODES = {{"off", "OFF"}, {"clips", "CLIPS"}, {"full", "FULL REPLAY"}};
    private static final String[][] VIDEO_MODES = {{"off", "OFF"}, {"manual", "MANUAL"}, {"buffer", "BUFFER"}};
    private static final String[][] FPS = {{"30", "30"}, {"60", "60"}};
    private static final String[][] QUALITIES = {{"standard", "STANDARD"}, {"high", "HIGH"}, {"ultra", "ULTRA"}};
    private static final String[][] RESOLUTIONS = {{"native", "NATIVE"}, {"1440", "1440P"}, {"1080", "1080P"}, {"720", "720P"}};
    private static final String[][] CODECS = {{"h264", "H.264"}, {"hevc", "HEVC"}};
    private static final String[][] SHOWS = {{"true", "HUD + MENUS"}, {"false", "WORLD ONLY"}};
    private static final String[][] OFF_ON = {{"false", "OFF"}, {"true", "ON"}};

    private final RemoteImages thumbs = new RemoteImages(320);
    private final RemoteImages full = new RemoteImages(2048);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "duskclient-media");
        t.setDaemon(true);
        return t;
    });

    private int tab;
    @Nullable private List<Mcpr.Entry> entries;
    private int generation;
    /** The file whose DELETE was clicked once; the second click deletes it. */
    @Nullable private Path confirming;
    /** The screenshot open full size, or -1. */
    private int viewing = -1;
    @Nullable private String note;
    private int cols, tileW, thumbH, tileH;

    public MediaScreen(@Nullable Screen parent, int tab) {
        super(Component.literal("Media"), parent);
        this.tab = tab;
        load();
    }

    // ---- data ---------------------------------------------------------------

    private Path dir() {
        return switch (tab) {
            case VIDEOS -> Mcpr.videosDir();
            case CLIPS -> Mcpr.clipsDir();
            case REPLAYS -> Mcpr.replaysDir();
            default -> Mcpr.screenshotsDir();
        };
    }

    private boolean recordings() {
        return tab == CLIPS || tab == REPLAYS;
    }

    private void load() {
        generation++;
        entries = null;
        confirming = null;
        viewing = -1;
        if (tab == SETTINGS) return;
        int gen = generation;
        Path dir = dir();
        String ext = recordings() ? ".mcpr" : tab == VIDEOS ? ".mp4" : ".png";
        worker.execute(() -> {
            List<Mcpr.Entry> list = Mcpr.list(dir, ext);
            Minecraft.getInstance().execute(() -> {
                if (gen == generation) entries = list;
            });
        });
    }

    private void delete(Mcpr.Entry e) {
        confirming = null;
        try {
            Files.deleteIfExists(e.path());
            if (tab == VIDEOS) Files.deleteIfExists(e.path().resolveSibling(".thumbs").resolve(e.name() + ".png"));
            thumbs.invalidate(key(e));
            full.invalidate(key(e));
            if (entries != null) {
                int i = entries.indexOf(e);
                entries = new ArrayList<>(entries);
                entries.remove(e);
                if (viewing >= 0) viewing = entries.isEmpty() ? -1 : Math.min(i, entries.size() - 1);
            }
            note = e.name() + " deleted.";
        } catch (IOException ex) {
            note = "Could not delete " + e.name() + ": " + ex.getMessage();
        }
        clampScroll();
    }

    private void watch(Mcpr.Entry e) {
        if (!canWatch()) return;
        String fail = MediaBackend.watch(e.path());
        if (fail != null) note = fail;
    }

    private boolean canWatch() {
        return MediaBackend.supported() && !inWorld();
    }

    private static String key(Mcpr.Entry e) {
        return e.path() + "@" + e.modified();
    }

    private byte @Nullable [] thumbBytes(Mcpr.Entry e) throws IOException {
        if (tab == VIDEOS) return MediaBackend.videoThumb(e.path());
        return recordings() ? Mcpr.readThumb(e.path()) : Files.readAllBytes(e.path());
    }

    private static String title(Mcpr.Entry e) {
        String n = e.name();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String when(long ms) {
        return new SimpleDateFormat("MMM d, HH:mm", Locale.ROOT).format(new Date(ms));
    }

    private String info(Mcpr.Entry e) {
        if (tab == VIDEOS) {
            String s = when(e.modified()) + " · " + Mcpr.size(e.size());
            return e.durationMs() > 0 ? Mcpr.duration(e.durationMs()) + " · " + s : s;
        }
        if (!recordings()) return when(e.modified()) + " · " + Mcpr.size(e.size());
        String s = Mcpr.duration(e.durationMs());
        if (!e.server().isEmpty()) s += " · " + e.server();
        return s + " · " + Mcpr.size(e.size());
    }

    static String clipKeyName() {
        KeyMapping k = DuskClient.clipKey();
        return k == null ? "the clip key" : k.getTranslatedKeyMessage().getString();
    }

    static String recordKeyName() {
        KeyMapping k = DuskClient.recordKey();
        return k == null ? "the record key" : k.getTranslatedKeyMessage().getString();
    }

    // ---- layout -------------------------------------------------------------

    @Override
    protected MenuFrame.Entry frameEntry() {
        return MenuFrame.Entry.MEDIA;
    }

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
        if (i == tab) return;
        tab = i;
        note = null;
        load();
    }

    @Override
    protected List<Tool> tools() {
        List<Tool> t = new ArrayList<>();
        t.add(new Tool("close", Icons.CLOSE, "", "Close"));
        if (tab != SETTINGS) t.add(new Tool("folder", Icons.FOLDER, "", "Open the " + dir().getFileName() + " folder"));
        return t;
    }

    @Override
    protected void onTool(String id) {
        if (id.equals("folder")) {
            try {
                Files.createDirectories(dir());
            } catch (IOException ignored) {
            }
            Mcpr.openInOs(dir());
        } else {
            super.onTool(id);
        }
    }

    private void layout() {
        layoutPanel();
        listX = px + pad;
        listY = bodyY() + pad;
        listW = px + pw - pad - BAR_W - 4 - listX;
        listBottom = py + ph - pad - 12;
        cols = Math.max(1, (listW + GAP) / (MIN_TILE + GAP));
        tileW = (listW - (cols - 1) * GAP) / cols;
        thumbH = (tileW - 8) * 9 / 16;
        tileH = 4 + thumbH + 4 + INFO_H + BTN_H + 4;
        clampScroll();
    }

    @Override
    protected int contentHeight() {
        if (tab == SETTINGS) {
            int h = 0;
            for (Row r : rows()) h += r.height() + GAP;
            return h - GAP;
        }
        int n = entries == null ? 0 : entries.size();
        int rows = (n + cols - 1) / Math.max(1, cols);
        return rows == 0 ? 0 : rows * (tileH + GAP) - GAP;
    }

    private int tileX(int i) {
        return listX + (i % cols) * (tileW + GAP);
    }

    private int tileY(int i) {
        return listY + (i / cols) * (tileH + GAP) - scroll;
    }

    // ---- drawing ------------------------------------------------------------

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        layout();
        drawPanel(c, mouseX, mouseY);
        boolean overlay = viewing >= 0;
        int mx = overlay ? -1 : mouseX, my = overlay ? -1 : mouseY;
        boolean hotList = inList(mx, my);
        c.scissor(listX, listY, listX + listW, listBottom);
        if (tab == SETTINGS) {
            drawSettings(c, mx, my);
        } else if (entries == null) {
            c.text("Loading...", listX + 4, listY + 4, Theme.TEXT_MUTED, false);
        } else if (entries.isEmpty()) {
            drawEmpty(c);
        } else {
            for (int i = 0; i < entries.size(); i++) {
                int y = tileY(i);
                if (y + tileH < listY || y > listBottom) continue;
                drawTile(c, entries.get(i), tileX(i), y, hotList ? mx : -1, hotList ? my : -1);
            }
        }
        c.unscissor();
        drawScrollbar(c);

        String foot = note;
        if (foot == null && recordings() && !MediaBackend.supported()) foot = "Replays need Minecraft 1.21.11 or newer.";
        else if (foot == null && recordings() && inWorld()) foot = "Leave the world to watch a recording.";
        else if (foot == null && tab == VIDEOS) foot = MediaBackend.videoStatus();
        else if (foot == null && tab != SETTINGS) foot = MediaBackend.status();
        if (foot != null) c.text(Theme.ellipsize(c, foot, listW), listX, py + ph - pad - 8, Theme.TEXT_MUTED, false);

        if (overlay) drawViewer(c, mouseX, mouseY);
    }

    private void drawEmpty(Canvas c) {
        String head, body;
        switch (tab) {
            case VIDEOS -> {
                head = "NO VIDEOS YET";
                body = MediaBackend.videoSupported()
                        ? "Turn on SCREEN RECORDING in SETTINGS, then press " + recordKeyName() + " in game to start and stop, or "
                                + clipKeyName() + " to save the buffer."
                        : "Screen recording needs Minecraft 1.21.11 or newer.";
            }
            case CLIPS -> {
                head = "NO CLIPS YET";
                body = "Set RECORDING to CLIPS in SETTINGS, then press " + clipKeyName() + " in game to save the last "
                        + DuskConfig.get().clipSeconds + " seconds.";
            }
            case REPLAYS -> {
                head = "NO REPLAYS YET";
                body = "Set RECORDING to FULL REPLAY in SETTINGS and every session is saved here when you leave.";
            }
            default -> {
                head = "NO SCREENSHOTS YET";
                body = "Press F2 in game.";
            }
        }
        int y = listY + 12;
        Theme.label(c, head, listX + (listW - Theme.labelWidth(c, head, 1.5f)) / 2, y, Theme.LABEL_UP, Theme.LABEL_LO, 1.5f);
        y += 20;
        for (String line : wrap(c, body, Math.min(listW, 300))) {
            c.text(line, listX + (listW - c.textWidth(line)) / 2, y, Theme.TEXT_MUTED, false);
            y += 10;
        }
    }

    private void drawTile(Canvas c, Mcpr.Entry e, int x, int y, int mx, int my) {
        boolean hot = Vanilla.inside(mx, my, x, y, tileW, tileH);
        Theme.plate(c, x, y, tileW, tileH, Theme.SURFACE, Theme.SURFACE, hot);
        int tx = x + 4, ty = y + 4, tw = tileW - 8;
        drawImage(c, thumbs, e, tx, ty, tw, thumbH);
        if (recordings() || tab == VIDEOS) {
            int bx = tx + 4, by = ty + thumbH - 15;
            if (Theme.modern()) Round.dot(c, bx, by, 11, 0xB0101012);
            else c.fill(bx, by, bx + 11, by + 11, 0xAA000000);
            Icons.PLAY.draw(c, bx + 2, by + 2, 0xFFFFFFFF);
        }
        int iy = ty + thumbH + 4;
        c.text(Theme.ellipsize(c, title(e), tw), tx, iy, Theme.TEXT, false);
        c.text(Theme.ellipsize(c, info(e), tw), tx, iy + 10, Theme.TEXT_FAINT, false);
        int[] open = openBox(x, y), del = deleteBox(x, y);
        boolean openEnabled = !recordings() || canWatch();
        boxButton(c, recordings() ? "WATCH" : tab == VIDEOS ? "OPEN" : "VIEW", open[0], open[1], open[2], open[3], mx, my,
                Theme.Family.GREY, false, openEnabled);
        boolean sure = e.path().equals(confirming);
        boxButton(c, sure ? "SURE?" : "DELETE", del[0], del[1], del[2], del[3], mx, my,
                sure ? Theme.Family.ACCENT : Theme.Family.GREY, false, true);
    }

    /** {@code e}'s image fitted inside the box, letterboxed; a placeholder while it loads or when there is none. */
    private void drawImage(Canvas c, RemoteImages images, Mcpr.Entry e, int x, int y, int w, int h) {
        if (Theme.modern()) Round.fill(c, x, y, w, h, Round.w(4), 0xFF141417);
        else c.fill(x, y, x + w, y + h, 0xFF101010);
        RemoteImages.Image img = images.get(key(e), () -> thumbBytes(e));
        if (img == null) {
            boolean moving = recordings() || tab == VIDEOS;
            Icons icon = moving ? Icons.MEDIA : Icons.FOLDER;
            if (images.failed(key(e)) || !moving) {
                icon.draw(c, x + (w - icon.width() * 2) / 2, y + (h - icon.height() * 2) / 2, Theme.pick(0xFF4A4A52, 0xFF3A3A3A), 2);
            }
            return;
        }
        float k = Math.min(w / (float) img.w(), h / (float) img.h());
        int dw = Math.round(img.w() * k), dh = Math.round(img.h() * k);
        c.push();
        c.translate(x + (w - dw) / 2f, y + (h - dh) / 2f);
        c.scale(k, k);
        c.blit(img.id(), 0, 0, 0, 0, img.w(), img.h(), img.w(), img.h());
        c.pop();
    }

    private int[] openBox(int x, int y) {
        return new int[] {x + 4, y + tileH - 4 - BTN_H, tileW - 8 - DEL_W - 4, BTN_H};
    }

    private int[] deleteBox(int x, int y) {
        return new int[] {x + tileW - 4 - DEL_W, y + tileH - 4 - BTN_H, DEL_W, BTN_H};
    }

    // ---- the full-size viewer -----------------------------------------------

    private int[] viewerButtons() {
        int w = Math.min(this.width - 40, 320), x = (this.width - w) / 2, y = this.height - 28;
        return new int[] {x, y, w, 18};
    }

    /** The viewer's buttons left to right: newer, open, delete, older, close. */
    private static final String[] VIEWER = {"<", "OPEN", "DELETE", ">", "CLOSE"};
    private static final int[] VIEWER_W = {1, 3, 3, 1, 3};

    private int[] viewerButton(int i) {
        int[] b = viewerButtons();
        int units = 0, before = 0;
        for (int j = 0; j < VIEWER_W.length; j++) {
            units += VIEWER_W[j];
            if (j < i) before += VIEWER_W[j];
        }
        int free = b[2] - (VIEWER.length - 1) * 4;
        int x = b[0] + before * free / units + i * 4;
        return new int[] {x, b[1], VIEWER_W[i] * free / units, b[3]};
    }

    private void drawViewer(Canvas c, int mouseX, int mouseY) {
        if (entries == null || viewing >= entries.size()) {
            viewing = -1;
            return;
        }
        Mcpr.Entry e = entries.get(viewing);
        c.fill(0, 0, this.width, this.height, Theme.pick(0xEB0B0B0D, 0xE6000000));
        int top = 12, bottom = this.height - 48;
        drawImage(c, full, e, 12, top, this.width - 24, bottom - top);
        String cap = title(e) + " · " + info(e) + " · " + (viewing + 1) + "/" + entries.size();
        c.text(Theme.ellipsize(c, cap, this.width - 24), (this.width - Math.min(c.textWidth(cap), this.width - 24)) / 2,
                bottom + 6, Theme.TEXT_MUTED, false);
        for (int i = 0; i < VIEWER.length; i++) {
            int[] b = viewerButton(i);
            boolean sure = i == 2 && e.path().equals(confirming);
            boolean enabled = switch (i) {
                case 0 -> viewing > 0;
                case 3 -> viewing < entries.size() - 1;
                default -> true;
            };
            boxButton(c, sure ? "SURE?" : VIEWER[i], b[0], b[1], b[2], b[3], mouseX, mouseY,
                    sure ? Theme.Family.ACCENT : Theme.Family.GREY, false, enabled);
        }
    }

    private boolean clickViewer(double mx, double my) {
        if (entries == null || viewing < 0 || viewing >= entries.size()) {
            viewing = -1;
            return true;
        }
        Mcpr.Entry e = entries.get(viewing);
        for (int i = 0; i < VIEWER.length; i++) {
            int[] b = viewerButton(i);
            if (!Vanilla.inside(mx, my, b[0], b[1], b[2], b[3])) continue;
            if (i != 2) confirming = null;
            switch (i) {
                case 0 -> step(-1);
                case 1 -> Mcpr.openInOs(e.path());
                case 2 -> {
                    if (e.path().equals(confirming)) delete(e);
                    else confirming = e.path();
                }
                case 3 -> step(1);
                default -> viewing = -1;
            }
            return true;
        }
        confirming = null;
        return true;
    }

    private void step(int d) {
        if (entries == null) return;
        confirming = null;
        viewing = Math.max(0, Math.min(entries.size() - 1, viewing + d));
    }

    // ---- settings -----------------------------------------------------------

    /** A settings row: a section heading, or a label with a line of help and its choices (none for a note). */
    private record Row(boolean heading, String label, String desc, String[][] opts, String current, boolean enabled,
                       @Nullable Consumer<String> pick) {
        static Row section(String title) {
            return new Row(true, title, "", new String[0][], "", false, null);
        }

        static Row note(String label, String desc) {
            return new Row(false, label, desc, new String[0][], "", false, null);
        }

        int height() {
            return heading ? SECTION_H : ROW_H;
        }
    }

    private static String[][] lengths(int[] seconds) {
        String[][] out = new String[seconds.length][];
        for (int i = 0; i < seconds.length; i++) {
            int s = seconds[i];
            out[i] = new String[] {Integer.toString(s), s < 60 || s % 60 != 0 ? s + "S" : s / 60 + "M"};
        }
        return out;
    }

    private static Consumer<String> video(Consumer<String> set) {
        return v -> {
            set.accept(v);
            DuskConfig.save();
            MediaBackend.videoSettingsChanged();
        };
    }

    private static Consumer<String> world(Consumer<String> set) {
        return v -> {
            set.accept(v);
            DuskConfig.save();
            MediaBackend.settingsChanged();
        };
    }

    private List<Row> rows() {
        DuskConfig cfg = DuskConfig.get();
        List<Row> rows = new ArrayList<>();
        boolean video = MediaBackend.videoSupported();
        boolean buffer = "buffer".equals(cfg.videoMode);
        rows.add(Row.section("SCREEN RECORDING"));
        rows.add(new Row(false, "MODE", video
                ? "Manual: " + recordKeyName() + " starts and stops. Buffer: also keeps the last stretch for " + clipKeyName() + "."
                : "Needs Minecraft 1.21.11 or newer.",
                VIDEO_MODES, cfg.videoMode, video, video(v -> cfg.videoMode = v)));
        rows.add(new Row(false, "BUFFER LENGTH", "How far back " + clipKeyName() + " reaches.", lengths(VIDEO_LENGTHS),
                Integer.toString(cfg.videoSeconds), video && buffer, video(v -> cfg.videoSeconds = Integer.parseInt(v))));
        rows.add(new Row(false, "FRAME RATE", "Frames a second.", FPS, Integer.toString(cfg.videoFps), video,
                video(v -> cfg.videoFps = Integer.parseInt(v))));
        rows.add(new Row(false, "QUALITY", "Higher keeps fast motion sharp and takes more space.", QUALITIES, cfg.videoQuality, video,
                video(v -> cfg.videoQuality = v)));
        rows.add(new Row(false, "RESOLUTION", "The tallest the video gets, never above the window.", RESOLUTIONS, cfg.videoResolution, video,
                video(v -> cfg.videoResolution = v)));
        rows.add(new Row(false, "CODEC", "HEVC is smaller where the GPU encodes it; H.264 plays anywhere.", CODECS, cfg.videoCodec, video,
                video(v -> cfg.videoCodec = v)));
        rows.add(new Row(false, "SHOWS", "The HUD and menus as you see them, or the world alone.", SHOWS, Boolean.toString(cfg.videoHud), video,
                video(v -> cfg.videoHud = Boolean.parseBoolean(v))));
        rows.add(new Row(false, "SOUND", "The game's sound, as you hear it.", OFF_ON, Boolean.toString(cfg.videoAudio), video,
                video(v -> cfg.videoAudio = Boolean.parseBoolean(v))));
        rows.add(new Row(false, "REC MARK", "The red mark in the corner while recording.", OFF_ON, Boolean.toString(cfg.videoIndicator), video,
                video(v -> cfg.videoIndicator = Boolean.parseBoolean(v))));
        rows.add(Row.note("KEYS", "Record: " + recordKeyName() + " · save the buffer: " + clipKeyName() + ". Change them in Controls."));
        rows.add(Row.note("STATUS", MediaBackend.videoStatus()));

        boolean supported = MediaBackend.supported();
        rows.add(Row.section("WORLD REPLAY"));
        rows.add(new Row(false, "RECORDING", supported ? "Off, a rolling buffer the clip key saves, or every session as a full replay."
                : "Needs Minecraft 1.21.11 or newer.", MODES, cfg.recordingMode, supported, world(v -> cfg.recordingMode = v)));
        rows.add(new Row(false, "CLIP LENGTH", "How far back a clip reaches.", lengths(CLIP_LENGTHS), Integer.toString(cfg.clipSeconds),
                supported && "clips".equals(cfg.recordingMode), world(v -> cfg.clipSeconds = Integer.parseInt(v))));
        rows.add(Row.note("SAVE A CLIP", "Press " + clipKeyName() + " in game; it saves the screen buffer too when that's on."));
        rows.add(Row.note("STATUS", MediaBackend.status()));
        return rows;
    }

    private interface ChoiceSink {
        /** @return true to stop */
        boolean choice(Row row, String value, String label, int x, int y, int w, int h, boolean on);
    }

    private int choicesWidth(Row row) {
        int w = 0;
        for (String[] o : row.opts()) w += this.font.width(o[1]) + 14 + 3;
        return w;
    }

    /** Walks the rows' choice buttons, right-aligned, so drawing and clicking share one layout. */
    private boolean choices(List<Row> rows, ChoiceSink sink) {
        int top = listY - scroll;
        for (Row row : rows) {
            int y = top + (ROW_H - BTN_H) / 2;
            int x = listX + listW - 6;
            for (int i = row.opts().length - 1; i >= 0; i--) {
                String[] o = row.opts()[i];
                int w = this.font.width(o[1]) + 14;
                x -= w;
                if (sink.choice(row, o[0], o[1], x, y, w, BTN_H, o[0].equals(row.current()))) return true;
                x -= 3;
            }
            top += row.height() + GAP;
        }
        return false;
    }

    private void drawSettings(Canvas c, int mx, int my) {
        List<Row> rows = rows();
        int y = listY - scroll;
        for (Row r : rows) {
            if (r.heading()) {
                Theme.label(c, r.label(), listX + 2, y + 6, Theme.LABEL_UP, Theme.LABEL_LO, 1f);
                c.fill(listX + Theme.labelWidth(c, r.label(), 1f) + 8, y + 9, listX + listW, y + 10, Theme.pick(0x22FFFFFF, 0x33FFFFFF));
            } else {
                Theme.plate(c, listX, y, listW, ROW_H, Theme.SURFACE, Theme.SURFACE, false);
                Theme.label(c, r.label(), listX + 8, y + 7, Theme.LABEL_UP, Theme.LABEL_LO, 1f);
                int textW = listW - 16 - (r.opts().length == 0 ? 0 : choicesWidth(r) + 6);
                c.text(Theme.ellipsize(c, r.desc(), Math.max(40, textW)), listX + 8, y + 20, Theme.TEXT_FAINT, false);
            }
            y += r.height() + GAP;
        }
        boolean hot = inList(mx, my);
        choices(rows, (row, value, label, x, by, w, h, on) -> {
            boxButton(c, label, x, by, w, h, hot ? mx : -1, hot ? my : -1, on ? Theme.Family.ACCENT : Theme.Family.GREY, false, row.enabled());
            return false;
        });
        // the picked choice reads through the disabled filter too
        choices(rows, (row, value, label, x, by, w, h, on) -> {
            if (on && !Theme.modern()) c.outline(x - 1, by - 1, w + 2, h + 2, row.enabled() ? Theme.ACCENT : 0x66FFFFFF);
            return false;
        });
    }

    private boolean clickSettings(double mx, double my) {
        return choices(rows(), (row, value, label, x, y, w, h, on) -> {
            if (!row.enabled() || row.pick() == null || !Vanilla.inside(mx, my, x, y, w, h)) return false;
            row.pick().accept(value);
            return true;
        });
    }

    // ---- input --------------------------------------------------------------

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        layout();
        if (viewing >= 0) return button != 0 || clickViewer(mx, my);
        if (clickChrome(mx, my, button)) return true;
        if (button != 0 || !inList(mx, my)) {
            confirming = null;
            return Vanilla.inside(mx, my, px, py, pw, ph);
        }
        if (tab == SETTINGS) return clickSettings(mx, my) || true;
        if (entries == null) return true;
        for (int i = 0; i < entries.size(); i++) {
            int x = tileX(i), y = tileY(i);
            if (!Vanilla.inside(mx, my, x, y, tileW, tileH)) continue;
            Mcpr.Entry e = entries.get(i);
            int[] del = deleteBox(x, y);
            if (Vanilla.inside(mx, my, del[0], del[1], del[2], del[3])) {
                if (e.path().equals(confirming)) delete(e);
                else confirming = e.path();
                return true;
            }
            confirming = null;
            int[] open = openBox(x, y);
            boolean onThumb = Vanilla.inside(mx, my, x + 4, y + 4, tileW - 8, thumbH);
            if (onThumb || Vanilla.inside(mx, my, open[0], open[1], open[2], open[3])) {
                if (recordings()) watch(e);
                else if (tab == VIDEOS) Mcpr.openInOs(e.path());
                else viewing = i;
            }
            return true;
        }
        confirming = null;
        return true;
    }

    @Override
    protected boolean menuScroll(double mx, double my, double amount) {
        if (viewing >= 0) {
            step(amount > 0 ? -1 : 1);
            return true;
        }
        return super.menuScroll(mx, my, amount);
    }

    @Override
    protected boolean menuKey(int key, int scancode, int modifiers) {
        if (viewing < 0) return super.menuKey(key, scancode, modifiers);
        switch (key) {
            case InputConstants.KEY_ESCAPE -> viewing = -1;
            case InputConstants.KEY_LEFT -> step(-1);
            case InputConstants.KEY_RIGHT -> step(1);
            default -> {
                if (!isSettingsKey(key, scancode)) return false;
                viewing = -1;
            }
        }
        return true;
    }

    @Override
    public void removed() {
        super.removed();
        thumbs.releaseAll();
        full.releaseAll();
        worker.shutdown();
    }
}
