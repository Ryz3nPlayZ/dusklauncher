package dev.dusk.client.gui;

/**
 * The launcher's animated background (launcher/src/background), redrawn
 * in-game: the 400x280 pixel scene cover-fit and bottom-anchored, sea and
 * flowers animated, bees drifting across, gold dust and the vignette. The
 * textures are the launcher's at 1.18x saturation.
 */
public final class TitleScene {
    private static final String DIR = "duskclient:textures/gui/title/";
    private static final String REAR = DIR + "rear.png", SEA = DIR + "sea.png",
            FOREGROUND = DIR + "foreground.png", FLOWER = DIR + "flower.png",
            BEE1 = DIR + "bee1.png", BEE2 = DIR + "bee2.png";
    private static final int SW = 400, SH = 280, FRAMES = 16;
    /** The launcher sizes mobs in CSS px at MOB_SCALE 0.45 over a scene drawn 3.2x (1280x800). */
    private static final float MOB_PX = 0.45f / 3.2f;

    // texture, size, top, drift s, delay s, bob s, amplitude, alpha (SceneBackground.tsx, overworld)
    private static final Object[][] BEES = {
            {BEE1, 168f, 0.26f, 38f, 0f, 6.33f, 18f, 0xFF},
            {BEE2, 144f, 0.48f, 46f, -8f, 7.67f, 14f, 0xFF},
            {BEE1, 120f, 0.18f, 32f, -18f, 5.33f, 12f, 0xBF},
    };

    private static final int DUST = 36;

    private TitleScene() {}

    public static void draw(Canvas c, int w, int h) {
        long now = System.currentTimeMillis();
        float s = Math.max(w / (float) SW, h / (float) SH);
        float ox = (w - SW * s) / 2f, oy = h - SH * s;

        c.fill(0, 0, w, h, 0xFF101820);
        c.push();
        c.translate(ox, oy);
        c.scale(s, s);
        c.blit(REAR, 0, 0, 0, 0, SW, SH, SW, SH);
        c.blit(SEA, 0, 0, 0, (int) (now / 83 % FRAMES) * SH, SW, SH, SW, SH * FRAMES);
        c.blit(FOREGROUND, 0, 0, 0, 0, SW, SH, SW, SH);
        c.blit(FLOWER, 0, 0, 0, (int) (now / 125 % FRAMES) * SH, SW, SH, SW, SH * FRAMES);
        c.pop();

        double t = now / 1000.0;
        for (Object[] b : BEES) {
            float size = (float) b[1] * MOB_PX * s;
            float drift = (float) b[3], delay = (float) b[4], bob = (float) b[5];
            double phase = ((t - delay) % drift + drift) % drift / drift;
            float x = (float) (w * (-0.14 + 1.28 * phase));
            double bobPhase = (t % bob) / bob;
            float y = h * (float) b[2] + (float) ((float) b[6] * MOB_PX * s * (1 - Math.cos(bobPhase * Math.PI * 2)) / 2);
            int frame = (int) (now / 83 % FRAMES);
            c.push();
            c.translate(x, y);
            c.scale(size / 64f, size / 64f);
            c.blit((String) b[0], 0, 0, 0, frame * 64, 64, 64, 64, 64 * FRAMES, ((int) b[7] << 24) | 0xFFFFFF);
            c.pop();
        }

        dust(c, w, h, t);
        vignette(c, w, h);
    }

    /** Floating gold motes (the Dawn mock's particles, #ffec85). */
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
            c.fill(px, py, px + size, py + size, (a << 24) | 0xFFEC85);
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
