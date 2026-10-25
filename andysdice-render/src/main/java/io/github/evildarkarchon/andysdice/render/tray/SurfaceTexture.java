package io.github.evildarkarchon.andysdice.render.tray;

import io.github.evildarkarchon.andysdice.core.tray.FloorSurface;
import io.github.evildarkarchon.andysdice.core.tray.WallMaterial;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * One of the Tray's procedural surfaces (ADR-0010): felt fibres, pebbled leather or walnut grain, generated in code
 * as a seamlessly tiling square of {@value #SIZE_PX}² texels with no external art. Ported from the tray prototype's
 * {@code TrayTextures}, with its numbers, except that the colour is left out: each texel holds a <em>shade</em>, a
 * linear multiplier about 1 that the Tray file's colour is tinted by in the shader, and a <em>height</em> for the bump
 * normal. So one texture per kind serves every colour a Tray file can give it.
 *
 * <p>Floors tile every 100 mm, about 10 texels per mm, so a felt fibre or a leather pebble is a few texels across.
 * Every pattern (fibres, Worley cells, value noise) wraps at the tile's edge. Generating one takes a few hundred
 * milliseconds on one thread; the same kind always comes out the same. Pure Java: no GL.
 */
public final class SurfaceTexture {

    /** The texture's side, in texels. */
    public static final int SIZE_PX = 1024;

    /**
     * The shade the upload's red byte 255 stands for. Shades run from about 0.4 to 1.25, so 2 leaves room above and
     * keeps each 8-bit step under 1% of a mid shade.
     */
    public static final float SHADE_RANGE = 2;

    /** The surfaces there are a texture for. */
    public enum Kind {
        /** Non-woven felt: short random fibres over soft mottling. */
        FELT,
        /** Pebbled leather: Worley cells with darker creases between them. */
        LEATHER,
        /** Flat-sawn walnut: soft growth rings and long pore streaks along u. */
        WOOD;

        /**
         * Returns the texture of a floor surface.
         *
         * @param surface the floor's surface
         * @return its kind
         */
        public static Kind of(FloorSurface surface) {
            return switch (surface) {
                case FELT -> FELT;
                case LEATHER -> LEATHER;
                case WOOD -> WOOD;
            };
        }

        /**
         * Returns the texture of a wall material.
         *
         * @param material the walls' material
         * @return its kind
         */
        public static Kind of(WallMaterial material) {
            return switch (material) {
                case WOOD -> WOOD;
                case LEATHER -> LEATHER;
            };
        }
    }

    private final Kind kind;
    private final float[] shade;
    private final float[] height;

    private SurfaceTexture(Kind kind, float[] shade, float[] height) {
        this.kind = kind;
        this.shade = shade;
        this.height = height;
    }

    /**
     * Generates a texture.
     *
     * @param kind which surface
     * @return the texture
     */
    public static SurfaceTexture generate(Kind kind) {
        Objects.requireNonNull(kind, "kind");
        return switch (kind) {
            case FELT -> felt(7);
            case LEATHER -> leather();
            case WOOD -> wood(3);
        };
    }

    /**
     * Returns which surface this is.
     *
     * @return the kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns the side in texels.
     *
     * @return {@value #SIZE_PX}
     */
    public int sizePx() {
        return SIZE_PX;
    }

    /**
     * Returns a texel's shade: the linear multiplier of the surface's colour there.
     *
     * @param x the column, 0 at the left
     * @param y the row, 0 at the top
     * @return the shade, about 1 on average, below {@link #SHADE_RANGE}
     */
    public float shade(int x, int y) {
        return shade[y * SIZE_PX + x];
    }

    /**
     * Returns a texel's height, which the shader scales to the surface's bump depth.
     *
     * @param x the column
     * @param y the row
     * @return 0..1
     */
    public float height(int x, int y) {
        return height[y * SIZE_PX + x];
    }

    /**
     * Writes the texture as {@code GL_RG8} texels, top row first: red = shade / {@link #SHADE_RANGE}, green = height.
     *
     * @param out a buffer with room for 2 × {@value #SIZE_PX}² bytes
     * @return {@code out}, advanced past them
     */
    public ByteBuffer put(ByteBuffer out) {
        for (int i = 0; i < shade.length; i++) {
            out.put((byte) Math.clamp(Math.round(shade[i] / SHADE_RANGE * 255), 0, 255));
            out.put((byte) Math.clamp(Math.round(height[i] * 255), 0, 255));
        }
        return out;
    }

    /** Felt: thousands of short random fibres, which wrap at the edge, over soft mottling. */
    private static SurfaceTexture felt(long seed) {
        int n = SIZE_PX;
        SplittableRandom random = new SplittableRandom(seed);
        float[] fibres = new float[n * n];
        for (int f = 0; f < 90_000; f++) {
            double x = random.nextDouble(n);
            double y = random.nextDouble(n);
            double a = random.nextDouble(Math.PI);
            int length = 4 + random.nextInt(16);
            float weight = (float) random.nextDouble(0.3, 1.0);
            for (int s = 0; s < length; s++) {
                int px = Math.floorMod((int) (x + Math.cos(a) * s), n);
                int py = Math.floorMod((int) (y + Math.sin(a) * s), n);
                fibres[py * n + px] += weight;
            }
        }
        float[] mottle = valueNoise(64, 64, seed + 11);
        float[] shade = new float[n * n];
        float[] height = new float[n * n];
        for (int i = 0; i < shade.length; i++) {
            float fibre = Math.min(fibres[i], 3f) / 3f;
            shade[i] = 0.80f + mottle[i] * 0.22f + fibre * 0.22f;
            height[i] = Math.min(1, (fibre * 200 + mottle[i] * 55) / 255);
        }
        return new SurfaceTexture(Kind.FELT, shade, height);
    }

    /** Pebbled leather: Worley cells for the grain, darker creases between them, broad mottling. */
    private static SurfaceTexture leather() {
        int n = SIZE_PX;
        SplittableRandom random = new SplittableRandom(21);
        int cells = 128; // one pebble about 0.8 mm across over a 100 mm tile
        float[][] points = new float[cells * cells][2];
        for (float[] p : points) {
            p[0] = (float) random.nextDouble();
            p[1] = (float) random.nextDouble();
        }
        float[] mottle = valueNoise(128, 128, 23);
        float[] shade = new float[n * n];
        float[] height = new float[n * n];
        float cellPx = n / (float) cells;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float fx = x / cellPx;
                float fy = y / cellPx;
                int cx = (int) fx;
                int cy = (int) fy;
                float f1 = 9;
                float f2 = 9;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int gx = cx + dx;
                        int gy = cy + dy;
                        // Cells wrap at the tile's edge, so the pebbles do too.
                        float[] p = points[Math.floorMod(gy, cells) * cells + Math.floorMod(gx, cells)];
                        float d = (float) Math.hypot(gx + p[0] - fx, gy + p[1] - fy);
                        if (d < f1) {
                            f2 = f1;
                            f1 = d;
                        } else if (d < f2) {
                            f2 = d;
                        }
                    }
                }
                float crease = smoothstep(0.0f, 0.25f, f2 - f1);
                int i = y * n + x;
                height[i] = crease * (1 - 0.35f * f1);
                shade[i] = (0.75f + 0.4f * mottle[i]) * (0.7f + 0.3f * crease);
            }
        }
        return new SurfaceTexture(Kind.LEATHER, shade, height);
    }

    /**
     * Flat-sawn walnut: soft, irregular growth rings plus long pore streaks along u. Rings and streaks come from noise
     * stretched along the grain, a whole number of cells per tile, so it wraps.
     */
    private static SurfaceTexture wood(long seed) {
        int n = SIZE_PX;
        float[] warp = valueNoise(256, 64, seed);
        float[] rings = valueNoise(512, 32, seed + 1);
        float[] pores = valueNoise(128, 2, seed + 2);
        float[] figure = valueNoise(64, 64, seed + 3);
        float[] shade = new float[n * n];
        float[] height = new float[n * n];
        for (int i = 0; i < shade.length; i++) {
            // Ring position: smooth bands whose spacing and phase wander along the board.
            float ring = rings[i] * 6 + warp[i] * 2.5f;
            float band = (float) (0.5 + 0.5 * Math.sin(ring * 2 * Math.PI));
            band = band * band * band;
            float pore = pores[i] < 0.28f ? 1 : 0;
            shade[i] = (1 - 0.32f * band) * (1 - 0.18f * pore) * (0.92f + 0.16f * figure[i]);
            height[i] = (140 - 60 * pore - 20 * band) / 255;
        }
        return new SurfaceTexture(Kind.WOOD, shade, height);
    }

    /** Smooth value noise in 0..1 with lattice spacings in texels along x and y (each dividing the side), wrapping. */
    private static float[] valueNoise(int cellX, int cellY, long seed) {
        int n = SIZE_PX;
        int gx = n / cellX;
        int gy = n / cellY;
        SplittableRandom random = new SplittableRandom(seed);
        float[] lattice = new float[gx * gy];
        for (int i = 0; i < lattice.length; i++) {
            lattice[i] = (float) random.nextDouble();
        }
        float[] out = new float[n * n];
        for (int y = 0; y < n; y++) {
            float fy = y / (float) cellY;
            int y0 = (int) fy;
            int y1 = (y0 + 1) % gy;
            float ty = smoothstep(0, 1, fy - y0);
            for (int x = 0; x < n; x++) {
                float fx = x / (float) cellX;
                int x0 = (int) fx;
                int x1 = (x0 + 1) % gx;
                float tx = smoothstep(0, 1, fx - x0);
                float a = lattice[y0 * gx + x0];
                float b = lattice[y0 * gx + x1];
                float c = lattice[y1 * gx + x0];
                float d = lattice[y1 * gx + x1];
                out[y * n + x] = (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty;
            }
        }
        return out;
    }

    /** GLSL's smoothstep. */
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3 - 2 * t);
    }
}
