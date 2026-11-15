package dev.dusk.client.render.map;

/** Diorama's default map style and how it lights a surface, in linear light. Ported under MIT, see NOTICE. */
final class MapLighting {
    static final float SATURATION = 1.35F;
    static final float EXPOSURE = 1.16F;
    static final float OUTLINE = 0.38F;
    static final float SKY_OCCLUSION = 0.85F;
    static final float AMBIENT = 0.34F;
    static final float SUN_STRENGTH = 0.62F;
    static final float HEIGHT_SHADE = 0.22F;
    static final float CORNER_OCCLUSION = 1.5F;
    static final double SUN_ELEVATION = Math.toRadians(38);
    static final float[] SUN_TINT = {1.14F, 1.0F, 0.8F};
    static final float[] SKY_TINT = {0.74F, 0.86F, 1.18F};

    private static final float CAVE_KNEE = 0.5F;
    private static final float CAVE_CEILING = 0.8F;

    private MapLighting() {
    }

    /** Light on a surface in the open; {@code lambert} is relative to a level top face, {@code sky} is how much sky it sees. */
    static void surface(double lambert, boolean shadowed, float ao, float sky, float emission, float facing, double y, float[] out) {
        float aoSun = Math.max(0, 1 - CORNER_OCCLUSION * (1 - ao));
        float aoSky = Math.min(ao, aoSun);
        float direct = lambert > 0 && !shadowed ? (float) (SUN_STRENGTH * lambert) * aoSun : 0;
        float ambient = AMBIENT * aoSky * (1 - SKY_OCCLUSION * (1 - (0.15F + 0.85F * sky)));
        float f = 1 + HEIGHT_SHADE * (float) Math.max(-1, Math.min(1, (y - 64) / 64));
        float r = (ambient * SKY_TINT[0] + direct * SUN_TINT[0]) * f;
        float g = (ambient * SKY_TINT[1] + direct * SUN_TINT[1]) * f;
        float b = (ambient * SKY_TINT[2] + direct * SUN_TINT[2]) * f;
        if (emission > 0) {
            float glow = 0.35F + emission * 0.85F;
            r = Math.max(r, glow);
            g = Math.max(g, glow);
            b = Math.max(b, glow);
        }
        out[0] = r * facing;
        out[1] = g * facing;
        out[2] = b * facing;
    }

    /** Light on a surface in a cave cut, from the game's own block and sky light; {@code fade} dims depth below the cut. */
    static void cave(int blockLight, int skyLight, float ao, float fade, float emission, float facing, float[] out) {
        float warm = (float) Math.pow(blockLight / 15.0F, 1.5);
        float cool = (float) Math.pow(skyLight / 15.0F * 0.8F, 1.5);
        float base = 0.42F;
        float r = (base * 0.92F + warm * 0.9F + cool * 0.6F) * ao * fade;
        float g = (base * 0.96F + warm * 0.75F + cool * 0.65F) * ao * fade;
        float b = (base * 1.08F + warm * 0.5F + cool * 0.75F) * ao * fade;
        float peak = Math.max(r, Math.max(g, b));
        if (peak > CAVE_KNEE) {
            float eased = CAVE_KNEE + (CAVE_CEILING - CAVE_KNEE) * (float) (1 - Math.exp(-(peak - CAVE_KNEE) / (CAVE_CEILING - CAVE_KNEE)));
            r *= eased / peak;
            g *= eased / peak;
            b *= eased / peak;
        }
        if (emission > 0) {
            float glow = 0.35F + emission * 0.85F;
            r = Math.max(r, glow);
            g = Math.max(g, glow);
            b = Math.max(b, glow);
        }
        out[0] = r * facing;
        out[1] = g * facing;
        out[2] = b * facing;
    }

    static float caveWater(int blockLight, int skyLight) {
        return 0.42F + (float) Math.pow(Math.max(blockLight, skyLight * 0.8F) / 15.0F, 1.5) * 0.8F;
    }

    static float luminance(float r, float g, float b) {
        return 0.2126F * r + 0.7152F * g + 0.0722F * b;
    }
}
