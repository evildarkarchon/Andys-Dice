package andysdice.prototype.tray;

import java.util.SplittableRandom;

/**
 * PROTOTYPE. Procedural, seamlessly tiling surface textures for the tray, the walls and the table. Each is
 * {@link #PX}^2 packed ARGB {@code int}s, top row first: RGB is sRGB-encoded albedo, A is a height
 * (0..255) the tray shader turns into a bump normal.
 *
 * <p>Floors tile every 100 mm (10 texels per mm), so a felt fibre or a leather pebble is a few texels
 * across; how much of that survives at the default render scale is one of the questions.
 */
final class TrayTextures {
    static final int PX = 1024;

    final int[] greenFelt = felt(0.030f, 0.105f, 0.052f, 7);
    final int[] navyFelt = felt(0.022f, 0.040f, 0.105f, 8);
    final int[] leather = leather();
    final int[] wood = wood(0.30f, 0.17f, 0.09f, 3);
    final int[] table = wood(0.11f, 0.065f, 0.04f, 4);

    /** Non-woven felt: thousands of short random fibres over soft mottling. */
    private static int[] felt(float r, float g, float b, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        float[] fibres = new float[PX * PX];
        for (int f = 0; f < 90_000; f++) {
            double x = rnd.nextDouble(PX), y = rnd.nextDouble(PX), a = rnd.nextDouble(Math.PI);
            int len = 4 + rnd.nextInt(16);
            float w = (float) rnd.nextDouble(0.3, 1.0);
            for (int s = 0; s < len; s++) {
                int px = Math.floorMod((int) (x + Math.cos(a) * s), PX), py = Math.floorMod((int) (y + Math.sin(a) * s), PX);
                fibres[py * PX + px] += w;
            }
        }
        float[] mottle = valueNoise(64, seed + 11);
        int[] out = new int[PX * PX];
        for (int i = 0; i < out.length; i++) {
            float fib = Math.min(fibres[i], 3f) / 3f;
            float m = 0.80f + mottle[i] * 0.22f + fib * 0.22f;
            out[i] = argb(Math.round(fib * 200 + mottle[i] * 55), srgb8(r * m), srgb8(g * m), srgb8(b * m));
        }
        return out;
    }

    /** Pebbled leather: Worley cells for the grain, darker creases between them, broad mottling. */
    private static int[] leather() {
        SplittableRandom rnd = new SplittableRandom(21);
        int cells = 128; // one pebble about 0.8 mm across over a 100 mm tile
        float[][] pts = new float[cells * cells][2];
        for (float[] p : pts) {
            p[0] = (float) rnd.nextDouble();
            p[1] = (float) rnd.nextDouble();
        }
        float[] mottle = valueNoise(128, 23);
        int[] out = new int[PX * PX];
        float cellPx = PX / (float) cells;
        for (int y = 0; y < PX; y++) {
            for (int x = 0; x < PX; x++) {
                float fx = x / cellPx, fy = y / cellPx;
                int cx = (int) fx, cy = (int) fy;
                float f1 = 9, f2 = 9;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int gx = cx + dx, gy = cy + dy;
                        float[] p = pts[Math.floorMod(gy, cells) * cells + Math.floorMod(gx, cells)];
                        float d = (float) Math.hypot(gx + p[0] - fx, gy + p[1] - fy);
                        if (d < f1) {
                            f2 = f1;
                            f1 = d;
                        } else if (d < f2) {
                            f2 = d;
                        }
                    }
                }
                float crease = Atlas.smoothstep(0.0f, 0.25f, f2 - f1);
                float h = crease * (1 - 0.35f * f1);
                int i = y * PX + x;
                float m = (0.75f + 0.4f * mottle[i]) * (0.7f + 0.3f * crease);
                out[i] = argb(Math.round(h * 255), srgb8(0.060f * m), srgb8(0.030f * m), srgb8(0.016f * m));
            }
        }
        return out;
    }

    /**
     * Walnut-ish flat-sawn wood: soft, irregular growth rings plus long pore streaks along u. Rings and
     * streaks come from noise stretched along the grain, a whole number of cells per tile, so it wraps.
     */
    private static int[] wood(float r, float g, float b, long seed) {
        float[] warp = valueNoise(256, 64, seed);
        float[] rings = valueNoise(512, 32, seed + 1);
        float[] pores = valueNoise(128, 2, seed + 2);
        float[] figure = valueNoise(64, 64, seed + 3);
        int[] out = new int[PX * PX];
        for (int y = 0; y < PX; y++) {
            for (int x = 0; x < PX; x++) {
                int i = y * PX + x;
                // Ring position: smooth bands whose spacing and phase wander along the board.
                float ring = rings[i] * 6 + warp[i] * 2.5f;
                float band = (float) (0.5 + 0.5 * Math.sin(ring * 2 * Math.PI));
                band = band * band * band;
                float pore = pores[i] < 0.28f ? 1 : 0;
                float k = (1 - 0.32f * band) * (1 - 0.18f * pore) * (0.92f + 0.16f * figure[i]);
                int h = Math.round(140 - 60 * pore - 20 * band);
                out[i] = argb(h, srgb8(r * k), srgb8(g * k), srgb8(b * k));
            }
        }
        return out;
    }

    /** Smooth value noise in 0..1 with lattice spacing {@code cellPx}, wrapping at the tile edge. */
    private static float[] valueNoise(int cellPx, long seed) {
        return valueNoise(cellPx, cellPx, seed);
    }

    /** Value noise with separate lattice spacings along x and y (each must divide {@link #PX}). */
    private static float[] valueNoise(int cellX, int cellY, long seed) {
        int gx = PX / cellX, gy = PX / cellY;
        SplittableRandom rnd = new SplittableRandom(seed);
        float[] lattice = new float[gx * gy];
        for (int i = 0; i < lattice.length; i++) lattice[i] = (float) rnd.nextDouble();
        float[] out = new float[PX * PX];
        for (int y = 0; y < PX; y++) {
            float fy = y / (float) cellY;
            int y0 = (int) fy, y1 = (y0 + 1) % gy;
            float ty = Atlas.smoothstep(0, 1, fy - y0);
            for (int x = 0; x < PX; x++) {
                float fx = x / (float) cellX;
                int x0 = (int) fx, x1 = (x0 + 1) % gx;
                float tx = Atlas.smoothstep(0, 1, fx - x0);
                float a = lattice[y0 * gx + x0], b = lattice[y0 * gx + x1];
                float c = lattice[y1 * gx + x0], d = lattice[y1 * gx + x1];
                out[y * PX + x] = (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty;
            }
        }
        return out;
    }

    private static int argb(int a, int r, int g, int b) {
        return Math.clamp(a, 0, 255) << 24 | (r & 0xff) << 16 | (g & 0xff) << 8 | (b & 0xff);
    }

    /** Linear 0..1 to sRGB-encoded 0..255. */
    private static int srgb8(float linear) {
        float c = Math.clamp(linear, 0f, 1f);
        float s = c <= 0.0031308f ? c * 12.92f : (float) (1.055 * Math.pow(c, 1 / 2.4) - 0.055);
        return Math.round(s * 255);
    }
}
