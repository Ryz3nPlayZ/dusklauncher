package dev.dusk.client.media;

import dev.dusk.client.compat.Component;
import dev.dusk.client.compat.InputConstants;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Icons;
import dev.dusk.client.gui.MenuFrame;
import dev.dusk.client.gui.PanelScreen;
import dev.dusk.client.gui.Round;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.gui.Vanilla;
import dev.dusk.client.modules.misc.Clips;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * MEDIA, as on 1.21.11: this instance's screenshots, recorded videos and
 * saved clips as thumbnail grids, and the screen recorder's settings.
 * Screenshots open full size here (← / → step through); videos and clips
 * open in the system's player. 1.8.9 has no world replays, so no REPLAYS tab.
 */
public class MediaScreen extends PanelScreen {
    public static final int SCREENSHOTS = 0, VIDEOS = 1, CLIPS = 2, SETTINGS = 3;
    private static final String[] TABS = {"SCREENSHOTS", "VIDEOS", "CLIPS", "SETTINGS"};
    private static final int GAP = 6, MIN_TILE = 132, INFO_H = 22, BTN_H = 16, DEL_W = 42, ROW_H = 34, SECTION_H = 18;
    private static final int[] LENGTHS = {15, 30, 60, 120, 300};
    private static final String[][] MODES = {{"off", "OFF"}, {"manual", "MANUAL"}, {"buffer", "BUFFER"}};
    private static final String[][] FPS = {{"30", "30"}, {"60", "60"}};
    private static final String[][] QUALITIES = {{"standard", "STANDARD"}, {"high", "HIGH"}, {"ultra", "ULTRA"}};
    private static final String[][] RESOLUTIONS = {{"native", "NATIVE"}, {"1440", "1440P"}, {"1080", "1080P"}, {"720", "720P"}};
    private static final String[][] CODECS = {{"h264", "H.264"}, {"hevc", "HEVC"}};
    private static final String[][] SHOWS = {{"true", "HUD + MENUS"}, {"false", "WORLD ONLY"}};
    private static final String[][] OFF_ON = {{"false", "OFF"}, {"true", "ON"}};

    /** A file in one of the folders. */
    static final class Entry {
        final Path path;
        final String name;
        final long modified, size;

        Entry(Path path, long modified, long size) {
            this.path = path;
            this.name = path.getFileName().toString();
            this.modified = modified;
            this.size = size;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "duskclient-media");
        t.setDaemon(true);
        return t;
    });
    private final MediaImages thumbs = new MediaImages(320, worker);
    private final MediaImages full = new MediaImages(2048, worker);

    private int tab;
    private List<Entry> entries;
    private int generation;
    /** The file whose DELETE was clicked once; the second click deletes it. */
    private Path confirming;
    /** The screenshot open full size, or -1. */
    private int viewing = -1;
    private String note;
    private int cols, tileW, thumbH, tileH;

    public MediaScreen(GuiScreen parent, int tab) {
        super(Component.literal("Media"), parent);
        this.tab = tab;
        load();
    }

    // ---- data ---------------------------------------------------------------

    private static Path screenshotsDir() {
        return Minecraft.getMinecraft().mcDataDir.toPath().resolve("screenshots");
    }

    private Path dir() {
        return tab == SCREENSHOTS ? screenshotsDir() : ScreenRecorder.videosDir();
    }

    private boolean videos() {
        return tab == VIDEOS || tab == CLIPS;
    }

    private void load() {
        generation++;
        entries = null;
        confirming = null;
        viewing = -1;
        if (tab == SETTINGS) return;
        final int gen = generation;
        final Path dir = dir();
        final int which = tab;
        worker.execute(() -> {
            final List<Entry> list = list(dir, which);
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (gen == generation) entries = list;
            });
        });
    }

    /** The tab's files, newest first: clips are the clip key's saves, videos everything else the recorder made. */
    private static List<Entry> list(Path dir, int tab) {
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean keep = tab == SCREENSHOTS ? n.endsWith(".png")
                        : n.endsWith(".mp4") && (tab == CLIPS) == n.startsWith("clip_");
                if (!keep || !Files.isRegularFile(p)) continue;
                try {
                    out.add(new Entry(p, Files.getLastModifiedTime(p).toMillis(), Files.size(p)));
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        Collections.sort(out, (a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    private static Path thumbFile(Entry e) {
        return e.path.resolveSibling(".thumbs").resolve(e.name + ".png");
    }

    private Path imageFile(Entry e) {
        return videos() ? thumbFile(e) : e.path;
    }

    private static String key(Entry e) {
        return e.path + "@" + e.modified;
    }

    private void delete(Entry e) {
        confirming = null;
        try {
            Files.deleteIfExists(e.path);
            if (videos()) Files.deleteIfExists(thumbFile(e));
            thumbs.invalidate(key(e));
            full.invalidate(key(e));
            if (entries != null) {
                int i = entries.indexOf(e);
                entries = new ArrayList<>(entries);
                entries.remove(e);
                if (viewing >= 0) viewing = entries.isEmpty() ? -1 : Math.min(i, entries.size() - 1);
            }
            note = e.name + " deleted.";
        } catch (IOException ex) {
            note = "Could not delete " + e.name + ": " + ex.getMessage();
        }
        clampScroll();
    }

    private void open(Entry e) {
        String err = ScreenRecorder.openInOs(e.path);
        if (err != null) note = "Could not open " + e.name + ": " + err;
    }

    private static String title(Entry e) {
        int dot = e.name.lastIndexOf('.');
        return dot > 0 ? e.name.substring(0, dot) : e.name;
    }

    private static String when(long ms) {
        return new SimpleDateFormat("MMM d, HH:mm", Locale.ROOT).format(new Date(ms));
    }

    private static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    private static String info(Entry e) {
        return when(e.modified) + " · " + size(e.size);
    }

    private static Clips clips() {
        return Clips.instance();
    }

    private static String clipKeyName() {
        return clips() == null ? "the clip key" : clips().clipKeyName();
    }

    private static String recordKeyName() {
        return clips() == null ? "the record key" : clips().recordKeyName();
    }

    /** The recorder's state in a line, for the foot of the video tabs. */
    private static String status() {
        Clips c = clips();
        if (c == null || c.mode().equals("off")) return "Screen recording is off. Turn it on in SETTINGS.";
        if (ScreenRecorder.recording()) return "Recording " + ScreenRecorder.duration(ScreenRecorder.recordingMs()) + ". Press "
                + recordKeyName() + " to stop.";
        if (c.mode().equals("buffer")) return "Holding the last " + c.seconds() + "s in a world: " + clipKeyName() + " saves it, "
                + recordKeyName() + " records.";
        return "Ready: press " + recordKeyName() + " in a world to start and stop a video.";
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
            String err = ScreenRecorder.openInOs(dir());
            if (err != null) note = "Could not open the folder: " + err;
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
    protected void relayout() {
        layout();
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
        if (foot == null && videos()) foot = status();
        if (foot != null) c.text(Theme.ellipsize(c, foot, listW), listX, py + ph - pad - 8, Theme.TEXT_MUTED, false);

        if (overlay) drawViewer(c, mouseX, mouseY);
    }

    private void drawEmpty(Canvas c) {
        String head, body;
        Clips cl = clips();
        boolean off = cl == null || cl.mode().equals("off");
        switch (tab) {
            case VIDEOS:
                head = "NO VIDEOS YET";
                body = off ? "Turn on SCREEN RECORDING in SETTINGS, then press " + recordKeyName() + " in game to start and stop."
                        : "Press " + recordKeyName() + " in game to start a video, and again to stop it.";
                break;
            case CLIPS:
                head = "NO CLIPS YET";
                body = cl != null && cl.mode().equals("buffer")
                        ? "Press " + clipKeyName() + " in game to save the last " + cl.seconds() + " seconds."
                        : "Set SCREEN RECORDING to BUFFER in SETTINGS, then press " + clipKeyName() + " in game to save the last stretch.";
                break;
            default:
                head = "NO SCREENSHOTS YET";
                body = "Press F2 in game.";
        }
        int y = listY + 12;
        Theme.label(c, head, listX + (listW - Theme.labelWidth(c, head, 1.5f)) / 2, y, Theme.LABEL_UP, Theme.LABEL_LO, 1.5f);
        y += 20;
        for (String line : wrap(c, body, Math.min(listW, 300))) {
            c.text(line, listX + (listW - c.textWidth(line)) / 2, y, Theme.TEXT_MUTED, false);
            y += 10;
        }
    }

    private void drawTile(Canvas c, Entry e, int x, int y, int mx, int my) {
        boolean hot = Vanilla.inside(mx, my, x, y, tileW, tileH);
        Theme.plate(c, x, y, tileW, tileH, Theme.SURFACE, Theme.SURFACE, hot);
        int tx = x + 4, ty = y + 4, tw = tileW - 8;
        drawImage(c, thumbs, e, tx, ty, tw, thumbH);
        if (videos()) {
            int bx = tx + 4, by = ty + thumbH - 15;
            if (Theme.modern()) Round.dot(c, bx, by, 11, 0xB0101012);
            else c.fill(bx, by, bx + 11, by + 11, 0xAA000000);
            Icons.PLAY.draw(c, bx + 2, by + 2, 0xFFFFFFFF);
        }
        int iy = ty + thumbH + 4;
        c.text(Theme.ellipsize(c, title(e), tw), tx, iy, Theme.TEXT, false);
        c.text(Theme.ellipsize(c, info(e), tw), tx, iy + 10, Theme.TEXT_FAINT, false);
        int[] open = openBox(x, y), del = deleteBox(x, y);
        boxButton(c, videos() ? "PLAY" : "VIEW", open[0], open[1], open[2], open[3], mx, my, Theme.Family.GREY, false, true);
        boolean sure = e.path.equals(confirming);
        boxButton(c, sure ? "SURE?" : "DELETE", del[0], del[1], del[2], del[3], mx, my,
                sure ? Theme.Family.ACCENT : Theme.Family.GREY, false, true);
    }

    /** {@code e}'s picture fitted inside the box, letterboxed; a placeholder while it loads or when there is none. */
    private void drawImage(Canvas c, MediaImages images, Entry e, int x, int y, int w, int h) {
        if (Theme.modern()) Round.fill(c, x, y, w, h, Round.w(4), 0xFF141417);
        else c.fill(x, y, x + w, y + h, 0xFF101010);
        MediaImages.Image img = images.get(key(e), images == full ? e.path : imageFile(e));
        if (img == null) {
            if (images.failed(key(e)) || !videos()) {
                Icons icon = videos() ? Icons.MEDIA : Icons.FOLDER;
                icon.draw(c, x + (w - icon.width() * 2) / 2, y + (h - icon.height() * 2) / 2, Theme.pick(0xFF4A4A52, 0xFF3A3A3A), 2);
            }
            return;
        }
        float k = Math.min(w / (float) img.w, h / (float) img.h);
        int dw = Math.round(img.w * k), dh = Math.round(img.h * k);
        c.push();
        c.translate(x + (w - dw) / 2f, y + (h - dh) / 2f);
        c.scale(k, k);
        c.blit(img.id, 0, 0, 0, 0, img.w, img.h, img.w, img.h);
        c.pop();
    }

    private int[] openBox(int x, int y) {
        return new int[] {x + 4, y + tileH - 4 - BTN_H, tileW - 8 - DEL_W - 4, BTN_H};
    }

    private int[] deleteBox(int x, int y) {
        return new int[] {x + tileW - 4 - DEL_W, y + tileH - 4 - BTN_H, DEL_W, BTN_H};
    }

    // ---- the full-size screenshot viewer ------------------------------------

    /** The viewer's buttons left to right: newer, open, delete, older, close. */
    private static final String[] VIEWER = {"<", "OPEN", "DELETE", ">", "CLOSE"};
    private static final int[] VIEWER_W = {1, 3, 3, 1, 3};

    private int[] viewerButton(int i) {
        int w = Math.min(this.width - 40, 320), bx = (this.width - w) / 2, by = this.height - 28;
        int units = 0, before = 0;
        for (int j = 0; j < VIEWER_W.length; j++) {
            units += VIEWER_W[j];
            if (j < i) before += VIEWER_W[j];
        }
        int free = w - (VIEWER.length - 1) * 4;
        return new int[] {bx + before * free / units + i * 4, by, VIEWER_W[i] * free / units, 18};
    }

    private void drawViewer(Canvas c, int mouseX, int mouseY) {
        if (entries == null || viewing >= entries.size()) {
            viewing = -1;
            return;
        }
        Entry e = entries.get(viewing);
        c.fill(0, 0, this.width, this.height, Theme.pick(0xEB0B0B0D, 0xE6000000));
        int top = 12, bottom = this.height - 48;
        drawImage(c, full, e, 12, top, this.width - 24, bottom - top);
        String cap = title(e) + " · " + info(e) + " · " + (viewing + 1) + "/" + entries.size();
        c.text(Theme.ellipsize(c, cap, this.width - 24), (this.width - Math.min(c.textWidth(cap), this.width - 24)) / 2,
                bottom + 6, Theme.TEXT_MUTED, false);
        for (int i = 0; i < VIEWER.length; i++) {
            int[] b = viewerButton(i);
            boolean sure = i == 2 && e.path.equals(confirming);
            boolean enabled = i == 0 ? viewing > 0 : i == 3 ? viewing < entries.size() - 1 : true;
            boxButton(c, sure ? "SURE?" : VIEWER[i], b[0], b[1], b[2], b[3], mouseX, mouseY,
                    sure ? Theme.Family.ACCENT : Theme.Family.GREY, false, enabled);
        }
    }

    private boolean clickViewer(double mx, double my) {
        if (entries == null || viewing < 0 || viewing >= entries.size()) {
            viewing = -1;
            return true;
        }
        Entry e = entries.get(viewing);
        for (int i = 0; i < VIEWER.length; i++) {
            int[] b = viewerButton(i);
            if (!Vanilla.inside(mx, my, b[0], b[1], b[2], b[3])) continue;
            if (i != 2) confirming = null;
            if (i == 0) step(-1);
            else if (i == 1) open(e);
            else if (i == 2) {
                if (e.path.equals(confirming)) delete(e);
                else confirming = e.path;
            } else if (i == 3) step(1);
            else viewing = -1;
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
    private static final class Row {
        final boolean heading, enabled;
        final String label, desc, current;
        final String[][] opts;
        final Consumer<String> pick;

        Row(boolean heading, String label, String desc, String[][] opts, String current, boolean enabled, Consumer<String> pick) {
            this.heading = heading;
            this.label = label;
            this.desc = desc;
            this.opts = opts;
            this.current = current;
            this.enabled = enabled;
            this.pick = pick;
        }

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

    private static String[][] lengths() {
        String[][] out = new String[LENGTHS.length][];
        for (int i = 0; i < LENGTHS.length; i++) {
            int s = LENGTHS[i];
            out[i] = new String[] {Integer.toString(s), s < 60 || s % 60 != 0 ? s + "S" : s / 60 + "M"};
        }
        return out;
    }

    /** A pick that saves the settings with the rest of the modules. */
    private static Consumer<String> save(Consumer<String> set) {
        return v -> {
            set.accept(v);
            saveModules();
        };
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        final Clips c = clips();
        if (c == null) {
            rows.add(Row.note("UNAVAILABLE", "The screen recorder didn't load."));
            return rows;
        }
        boolean on = !c.mode().equals("off");
        rows.add(Row.section("SCREEN RECORDING"));
        rows.add(new Row(false, "MODE", "Manual: " + recordKeyName() + " starts and stops. Buffer: also keeps the last stretch for "
                + clipKeyName() + ".", MODES, c.mode(), true, save(c::setMode)));
        rows.add(new Row(false, "BUFFER LENGTH", "How far back " + clipKeyName() + " reaches.", lengths(),
                Integer.toString(c.seconds()), c.mode().equals("buffer"), save(v -> c.setSeconds(Integer.parseInt(v)))));
        rows.add(new Row(false, "FRAME RATE", "Frames a second.", FPS, Integer.toString(c.fps()), on, save(c::setFps)));
        rows.add(new Row(false, "QUALITY", "Higher keeps fast motion sharp and takes more space.", QUALITIES, c.quality(), on,
                save(c::setQuality)));
        rows.add(new Row(false, "RESOLUTION", "The tallest the video gets, never above the window.", RESOLUTIONS, c.resolution(), on,
                save(c::setResolution)));
        rows.add(new Row(false, "CODEC", "HEVC is smaller; H.264 plays anywhere.", CODECS, c.codec(), on, save(c::setCodec)));
        rows.add(new Row(false, "SHOWS", "The HUD and menus as you see them, or the world alone.", SHOWS, Boolean.toString(c.hud()), on,
                save(v -> c.setHud(Boolean.parseBoolean(v)))));
        rows.add(new Row(false, "REC MARK", "The red mark in the corner while recording.", OFF_ON, Boolean.toString(c.indicator()), on,
                save(v -> c.setIndicator(Boolean.parseBoolean(v)))));
        rows.add(Row.note("KEYS", "Record: " + recordKeyName() + " · save the buffer: " + clipKeyName() + ". Change them in Keybinds."));
        rows.add(Row.note("SOUND", "Videos are silent for now: the game's sound can't be captured on 1.8.9 yet."));
        rows.add(Row.note("STATUS", status()));
        return rows;
    }

    private interface ChoiceSink {
        /** @return true to stop */
        boolean choice(Row row, String value, String label, int x, int y, int w, int h, boolean on);
    }

    private int choicesWidth(Row row) {
        int w = 0;
        for (String[] o : row.opts) w += this.fontRendererObj.getStringWidth(o[1]) + 14 + 3;
        return w;
    }

    /** Walks the rows' choice buttons, right-aligned, so drawing and clicking share one layout. */
    private boolean choices(List<Row> rows, ChoiceSink sink) {
        int top = listY - scroll;
        for (Row row : rows) {
            int y = top + (ROW_H - BTN_H) / 2;
            int x = listX + listW - 6;
            for (int i = row.opts.length - 1; i >= 0; i--) {
                String[] o = row.opts[i];
                int w = this.fontRendererObj.getStringWidth(o[1]) + 14;
                x -= w;
                if (sink.choice(row, o[0], o[1], x, y, w, BTN_H, o[0].equals(row.current))) return true;
                x -= 3;
            }
            top += row.height() + GAP;
        }
        return false;
    }

    private void drawSettings(final Canvas c, int mx, int my) {
        List<Row> rows = rows();
        int y = listY - scroll;
        for (Row r : rows) {
            if (r.heading) {
                Theme.label(c, r.label, listX + 2, y + 6, Theme.LABEL_UP, Theme.LABEL_LO, 1f);
                c.fill(listX + Theme.labelWidth(c, r.label, 1f) + 8, y + 9, listX + listW, y + 10, Theme.pick(0x22FFFFFF, 0x33FFFFFF));
            } else {
                Theme.plate(c, listX, y, listW, ROW_H, Theme.SURFACE, Theme.SURFACE, false);
                Theme.label(c, r.label, listX + 8, y + 7, Theme.LABEL_UP, Theme.LABEL_LO, 1f);
                int textW = listW - 16 - (r.opts.length == 0 ? 0 : choicesWidth(r) + 6);
                c.text(Theme.ellipsize(c, r.desc, Math.max(40, textW)), listX + 8, y + 20, Theme.TEXT_FAINT, false);
            }
            y += r.height() + GAP;
        }
        final boolean hot = inList(mx, my);
        final int hx = hot ? mx : -1, hy = hot ? my : -1;
        choices(rows, (row, value, label, x, by, w, h, on) -> {
            boxButton(c, label, x, by, w, h, hx, hy, on ? Theme.Family.ACCENT : Theme.Family.GREY, false, row.enabled);
            return false;
        });
        // the picked choice reads through the disabled filter too
        choices(rows, (row, value, label, x, by, w, h, on) -> {
            if (on && !Theme.modern()) c.outline(x - 1, by - 1, w + 2, h + 2, row.enabled ? Theme.ACCENT : 0x66FFFFFF);
            return false;
        });
    }

    private boolean clickSettings(final double mx, final double my) {
        return choices(rows(), (row, value, label, x, y, w, h, on) -> {
            if (!row.enabled || row.pick == null || !Vanilla.inside(mx, my, x, y, w, h)) return false;
            row.pick.accept(value);
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
        if (tab == SETTINGS) {
            clickSettings(mx, my);
            return true;
        }
        if (entries == null) return true;
        for (int i = 0; i < entries.size(); i++) {
            int x = tileX(i), y = tileY(i);
            if (!Vanilla.inside(mx, my, x, y, tileW, tileH)) continue;
            Entry e = entries.get(i);
            int[] del = deleteBox(x, y);
            if (Vanilla.inside(mx, my, del[0], del[1], del[2], del[3])) {
                if (e.path.equals(confirming)) delete(e);
                else confirming = e.path;
                return true;
            }
            confirming = null;
            int[] open = openBox(x, y);
            boolean onThumb = Vanilla.inside(mx, my, x + 4, y + 4, tileW - 8, thumbH);
            if (onThumb || Vanilla.inside(mx, my, open[0], open[1], open[2], open[3])) {
                if (videos()) open(e);
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
        if (key == InputConstants.KEY_ESCAPE) viewing = -1;
        else if (key == InputConstants.KEY_LEFT) step(-1);
        else if (key == InputConstants.KEY_RIGHT) step(1);
        else if (isSettingsKey(key, scancode)) viewing = -1;
        else return false;
        return true;
    }

    @Override
    protected void removed() {
        super.removed();
        thumbs.releaseAll();
        full.releaseAll();
        worker.shutdown();
    }
}
