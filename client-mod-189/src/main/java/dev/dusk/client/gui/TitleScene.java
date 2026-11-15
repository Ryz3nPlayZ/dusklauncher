package dev.dusk.client.gui;

/**
 * The launcher's animated background (launcher/src/background), redrawn
 * in-game: the 400x280 pixel scene cover-fit and bottom-anchored, the sea
 * (and the dusk flowers) animated, mobs drifting across, embers and the
 * vignette. The textures are the launcher's at 1.18x saturation; the ghasts
 * are stored mirrored, as the launcher flips them.
 *
 * Which scene follows the launcher's theme: it passes -Ddusk.scene=nether
 * when NETHER is picked, and anything else is the dusk scene.
 */
public final class TitleScene {
    private static final String DIR = "duskclient:textures/gui/title/";
    private static final String NETHER_DIR = DIR + "nether/";
    private static final int SW = 400, SH = 280, FRAMES = 16;
    /** The launcher sizes mobs in CSS px at MOB_SCALE 0.45 over a scene drawn 3.2x (1280x800). */
    private static final float MOB_PX = 0.45f / 3.2f;

    /** One drifting mob (SceneBackground.tsx): texture strip, frame size and time, then the launcher's numbers. */
    private static final class Mob {
        final String tex;
        final int framePx, frameMs, alpha;
        final float size, top, drift, delay, bob, amp;

        Mob(String tex, int framePx, int frameMs, float size, float top, float drift, float delay, float bob, float amp, int alpha) {
            this.tex = tex;
            this.framePx = framePx;
            this.frameMs = frameMs;
            this.size = size;
            this.top = top;
            this.drift = drift;
            this.delay = delay;
            this.bob = bob;
            this.amp = amp;
            this.alpha = alpha;
        }
    }

    private static final class Scene {
        final int sky;
        final String rear, sea, foreground, flower; // flower may be null
        final Mob[] mobs;

        Scene(int sky, String dir, boolean flower, Mob... mobs) {
            this.sky = sky;
            this.rear = dir + "rear.png";
            this.sea = dir + "sea.png";
            this.foreground = dir + "foreground.png";
            this.flower = flower ? dir + "flower.png" : null;
            this.mobs = mobs;
        }
    }

    private static final Scene DUSK = new Scene(0xFF18103A, DIR, true,
            new Mob(DIR + "bee1.png", 64, 83, 168f, 0.26f, 38f, 0f, 6.33f, 18f, 0xFF),
            new Mob(DIR + "bee2.png", 64, 83, 144f, 0.48f, 46f, -8f, 7.67f, 14f, 0xFF),
            new Mob(DIR + "bee1.png", 64, 83, 120f, 0.18f, 32f, -18f, 5.33f, 12f, 0xBF));

    private static final Scene NETHER = new Scene(0xFF1C0505, NETHER_DIR, false,
            new Mob(NETHER_DIR + "ghast_big.png", 80, 166, 288f, 0.12f, 72f, 0f, 9f, 18f, 0xFF),
            new Mob(NETHER_DIR + "ghast_small.png", 64, 166, 180f, 0.22f, 58f, -22f, 7.25f, 14f, 0xD9),
            new Mob(NETHER_DIR + "ghast_big.png", 80, 166, 240f, 0.32f, 88f, -48f, 11f, 22f, 0x99));

    private static final int DUST = 36;

    private TitleScene() {}

    /** The launcher's NETHER theme, read once: the launcher sets it per launch. */
    private static final boolean NETHER_THEME;

    static {
        String s = System.getProperty("dusk.scene", "").trim();
        NETHER_THEME = s.equalsIgnoreCase("nether") || s.equalsIgnoreCase("mcpvp");
    }

    public static void draw(Canvas c, int w, int h) {
        Scene scene = NETHER_THEME ? NETHER : DUSK;
        long now = System.currentTimeMillis();
        float s = Math.max(w / (float) SW, h / (float) SH);
        float ox = (w - SW * s) / 2f, oy = h - SH * s;

        c.fill(0, 0, w, h, scene.sky);
        c.push();
        c.translate(ox, oy);
        c.scale(s, s);
        c.blit(scene.rear, 0, 0, 0, 0, SW, SH, SW, SH);
        c.blit(scene.sea, 0, 0, 0, (int) (now / 83 % FRAMES) * SH, SW, SH, SW, SH * FRAMES);
        c.blit(scene.foreground, 0, 0, 0, 0, SW, SH, SW, SH);
        if (scene.flower != null) {
            c.blit(scene.flower, 0, 0, 0, (int) (now / 125 % FRAMES) * SH, SW, SH, SW, SH * FRAMES);
        }
        c.pop();

        double t = now / 1000.0;
        for (Mob m : scene.mobs) {
            // the launcher's mob width is size * MOB_SCALE css px, the whole frame
            float px = m.size * MOB_PX * s;
            double phase = ((t - m.delay) % m.drift + m.drift) % m.drift / m.drift;
            float x = (float) (w * (-0.14 + 1.28 * phase));
            double bobPhase = (t % m.bob) / m.bob;
            float y = h * m.top + (float) (m.amp * MOB_PX * s * (1 - Math.cos(bobPhase * Math.PI * 2)) / 2);
            int f = m.framePx;
            int frame = (int) (now / m.frameMs % FRAMES);
            c.push();
            c.translate(x, y);
            c.scale(px / f, px / f);
            c.blit(m.tex, 0, 0, 0, frame * f, f, f, f, f * FRAMES, (m.alpha << 24) | 0xFFFFFF);
            c.pop();
        }

        dust(c, w, h, t);
        vignette(c, w, h);
    }

    /** Floating embers, warm against the sky. */
    private static void dust(Canvas c, int w, int h, double t) {
        for (int i = 0; i < DUST; i++) {
            double r1 = hash(i * 3 + 1), r2 = hash(i * 3 + 2), r3 = hash(i * 3 + 3);
            double speed = 4 + r2 * 10; // gui px per second, upwards
            double travel = h + 20;
            double y = h + 10 - ((t * speed + r3 * travel) % travel);
            double x = r1 * w + Math.sin(t * 0.4 + i) * 6;
            double twinkle = 0.35 + 0.45 * (0.5 + 0.5 * Math.sin(t * (1.2 + r2) + i * 1.7));
            int a = (int) (twinkle * 255 * Math.min(1, y / (h * 0.35)));
            if (a <= 4) continue;
            int size = r3 > 0.8 ? 2 : 1;
            int px = (int) x, py = (int) y;
            c.fill(px, py, px + size, py + size, (a << 24) | 0xFFC99A);
        }
    }
    private static double hash(int n) {
        long x = n * 0x9E3779B97F4A7C15L;
        x ^= x >>> 31;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 29;
        return (x >>> 11) / (double) (1L << 53);
    }

    /** radial(120% 90% at 50% 42%, transparent 55%, black .5) + linear top/bottom darkening. */
    private static void vignette(Canvas c, int w, int h) {
        c.fillGradient(0, 0, w, (int) (h * 0.22f), 0x1F000000, 0x00000000);
        c.fillGradient(0, (int) (h * 0.70f), w, h, 0x00000000, 0x6B000000);
        int edge = Math.max(8, w / 5);
        for (int i = 0; i < edge; i++) {
            float k = 1f - i / (float) edge;
            int a = (int) (0x80 * k * k);
            if (a == 0) continue;
            int col = a << 24;
            c.fill(i, 0, i + 1, h, col);
            c.fill(w - i - 1, 0, w - i, h, col);
        }
        int band = Math.max(6, h / 6);
        for (int i = 0; i < band; i++) {
            float k = 1f - i / (float) band;
            int a = (int) (0x50 * k * k);
            if (a == 0) continue;
            c.fill(0, h - i - 1, w, h - i, a << 24);
        }
    }
}
