package andysdice.prototype.materials;

import java.util.SplittableRandom;

/**
 * PROTOTYPE. Felt and wood for the tray, copied from the renderer bake-off so the dice are judged in
 * the same context. Packed ARGB {@code int[]} rows, top row first, sRGB-encoded.
 */
final class TrayTextures {
    static final int FELT_W = 2048, FELT_H = 1290;
    static final int WOOD_PX = 1024;

    final int[] felt = felt();
    final int[] wood = wood();

    /** Dark green tray felt: fine fibre noise over soft mottling. */
    private static int[] felt() {
        SplittableRandom rnd = new SplittableRandom(7);
        float[] mottle = valueNoise(FELT_W, FELT_H, 64, 11);
        int[] out = new int[FELT_W * FELT_H];
        for (int i = 0; i < out.length; i++) {
            float fibre = (float) rnd.nextDouble() * 0.16f - 0.08f;
            float m = 0.88f + mottle[i] * 0.2f + fibre;
            out[i] = argb(srgb8(0.030f * m), srgb8(0.105f * m), srgb8(0.052f * m));
        }
        return out;
    }

    /** Walnut-ish wood: warped rings running along u. */
    private static int[] wood() {
        float[] warp = valueNoise(WOOD_PX, WOOD_PX, 128, 3);
        float[] fine = valueNoise(WOOD_PX, WOOD_PX, 6, 5);
        int[] out = new int[WOOD_PX * WOOD_PX];
        for (int y = 0; y < WOOD_PX; y++) {
            for (int x = 0; x < WOOD_PX; x++) {
                int i = y * WOOD_PX + x;
                float rings = (float) Math.sin((y / (float) WOOD_PX * 150 + warp[i] * 4) * Math.PI);
                float t = 0.5f + 0.5f * rings;
                t = t * t;
                float g = 0.92f + fine[i] * 0.16f;
                float r = (0.30f * (1 - t) + 0.16f * t) * g, gg = (0.17f * (1 - t) + 0.085f * t) * g, b = (0.09f * (1 - t) + 0.045f * t) * g;
                out[i] = argb(srgb8(r), srgb8(gg), srgb8(b));
            }
        }
        return out;
    }

    /** Smooth value noise in 0..1 with lattice spacing {@code cellPx}. */
    private static float[] valueNoise(int w, int h, int cellPx, long seed) {
        int gw = w / cellPx + 2, gh = h / cellPx + 2;
        SplittableRandom rnd = new SplittableRandom(seed);
        float[] lattice = new float[gw * gh];
        for (int i = 0; i < lattice.length; i++) lattice[i] = (float) rnd.nextDouble();
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            float fy = y / (float) cellPx;
            int y0 = (int) fy;
            float ty = Atlas.smoothstep(0, 1, fy - y0);
            for (int x = 0; x < w; x++) {
                float fx = x / (float) cellPx;
                int x0 = (int) fx;
                float tx = Atlas.smoothstep(0, 1, fx - x0);
                float a = lattice[y0 * gw + x0], b = lattice[y0 * gw + x0 + 1];
                float c = lattice[(y0 + 1) * gw + x0], d = lattice[(y0 + 1) * gw + x0 + 1];
                out[y * w + x] = (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty;
            }
        }
        return out;
    }

    private static int argb(int r, int g, int b) {
        return 0xff << 24 | (r & 0xff) << 16 | (g & 0xff) << 8 | (b & 0xff);
    }

    /** Linear 0..1 to sRGB-encoded 0..255. */
    private static int srgb8(float linear) {
        float c = Math.clamp(linear, 0f, 1f);
        float s = c <= 0.0031308f ? c * 12.92f : (float) (1.055 * Math.pow(c, 1 / 2.4) - 0.055);
        return Math.round(s * 255);
    }
}
