package andysdice.prototype.bakeoff;

import org.joml.Vector2f;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * PROTOTYPE. Procedural textures shared by both variants, as packed ARGB {@code int[]} rows (top row
 * first, so UV v=0 is the top of the image in both JavaFX and OpenGL).
 *
 * <p>The numeral atlas follows the research on how dice are made: numerals are recessed and filled
 * with ink flush with the recess floor, the face around them is polished bare.
 */
final class Textures {
    static final int CELL_PX = 512;
    static final int ATLAS_PX = CELL_PX * D20.ATLAS_GRID;
    /** Engraving depth in mm; research guesses 0.3-0.5 mm. */
    static final float ENGRAVE_DEPTH_MM = 0.4f;
    /** The face triangle's base spans 0.88 of a cell and equals the kernel edge (~12.04 mm). */
    static final float CELL_MM = 12.04f / 0.88f;

    /** R = ink coverage, G = surface height (1 = face, 0 = recess floor). */
    final int[] atlasMask;
    /** Tangent-space normal map (u right, v down, n out) of the engraving. */
    final int[] atlasNormal;
    final float[] ink;
    final float[] height;
    final String fontUsed;

    static final int FELT_W = 2048, FELT_H = 1290;
    final int[] felt;
    static final int WOOD_PX = 1024;
    final int[] wood;
    static final int SHADOW_PX = 256;
    final int[] blobShadow;

    Textures() {
        Font font = pickFont();
        fontUsed = font.getFontName();
        ink = renderNumerals(font);
        height = engraveHeight(ink);
        atlasMask = new int[ATLAS_PX * ATLAS_PX];
        for (int i = 0; i < atlasMask.length; i++) {
            atlasMask[i] = argb(255, Math.round(ink[i] * 255), Math.round(height[i] * 255), 0);
        }
        atlasNormal = normalMap(height, ATLAS_PX, ATLAS_PX, ENGRAVE_DEPTH_MM * CELL_PX / CELL_MM);
        felt = felt();
        wood = wood();
        blobShadow = blobShadow();
    }

    /**
     * Chessex uses its own bold condensed sans. Bahnschrift (shipped with Windows 10+) is the closest
     * system font; otherwise fall back to a bold sans that we squash horizontally in {@link #glyph}.
     */
    private static Font pickFont() {
        List<String> families = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String name : new String[]{"Bahnschrift SemiBold Condensed", "Bahnschrift Condensed", "Bahnschrift", "Arial Narrow"}) {
            if (families.contains(name)) return new Font(name, Font.BOLD, 200);
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, 200);
    }

    private static Shape glyph(Font font, String text) {
        FontRenderContext frc = new FontRenderContext(null, true, true);
        Shape s = font.createGlyphVector(frc, text).getOutline();
        boolean condensed = font.getFontName().contains("Condensed") || font.getFontName().contains("Narrow");
        return condensed ? s : AffineTransform.getScaleInstance(0.82, 1).createTransformedShape(s);
    }

    /** Draws 1..20 centred on each cell's triangle, as large as fits, 6 and 9 underlined (Chessex style). */
    private static float[] renderNumerals(Font font) {
        BufferedImage img = new BufferedImage(ATLAS_PX, ATLAS_PX, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, ATLAS_PX, ATLAS_PX);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1f));
        // The triangle's incircle centre, in cell-local units.
        Vector2f centroid = new Vector2f(D20.CELL_TOP).add(D20.CELL_BL).add(D20.CELL_BR).div(3);
        for (int n = 1; n <= 20; n++) {
            Area shape = new Area(glyph(font, Integer.toString(n)));
            Rectangle2D gb = shape.getBounds2D();
            if (n == 6 || n == 9) {
                double barH = gb.getHeight() * 0.09, gap = gb.getHeight() * 0.08;
                shape.add(new Area(new Rectangle2D.Double(gb.getX() + gb.getWidth() * 0.08, gb.getMaxY() + gap, gb.getWidth() * 0.84, barH)));
            }
            Rectangle2D b = shape.getBounds2D();
            double maxW = 0.50 * CELL_PX, maxH = 0.36 * CELL_PX;
            double scale = Math.min(maxW / b.getWidth(), maxH / b.getHeight());
            int cell = n - 1;
            double cx = (cell % D20.ATLAS_GRID + centroid.x) * CELL_PX;
            double cy = (cell / D20.ATLAS_GRID + centroid.y) * CELL_PX;
            AffineTransform at = new AffineTransform();
            at.translate(cx, cy);
            at.scale(scale, scale);
            at.translate(-b.getCenterX(), -b.getCenterY());
            g.fill(at.createTransformedShape(shape));
        }
        g.dispose();
        byte[] px = ((java.awt.image.DataBufferByte) img.getRaster().getDataBuffer()).getData();
        float[] out = new float[px.length];
        for (int i = 0; i < px.length; i++) out[i] = (px[i] & 0xff) / 255f;
        return out;
    }

    /** Recess profile: blur the glyph mask a little so the engraving walls slope (~0.1 mm). */
    private static float[] engraveHeight(float[] mask) {
        float[] h = boxBlur(boxBlur(mask, ATLAS_PX, ATLAS_PX, 2), ATLAS_PX, ATLAS_PX, 2);
        for (int i = 0; i < h.length; i++) h[i] = 1f - Math.min(1f, h[i] * 1.15f);
        return h;
    }

    private static float[] boxBlur(float[] src, int w, int h, int r) {
        float[] tmp = new float[src.length], dst = new float[src.length];
        float inv = 1f / (2 * r + 1);
        for (int y = 0; y < h; y++) {
            float acc = 0;
            for (int x = -r; x <= r; x++) acc += src[y * w + Math.clamp(x, 0, w - 1)];
            for (int x = 0; x < w; x++) {
                tmp[y * w + x] = acc * inv;
                acc += src[y * w + Math.min(x + r + 1, w - 1)] - src[y * w + Math.max(x - r, 0)];
            }
        }
        for (int x = 0; x < w; x++) {
            float acc = 0;
            for (int y = -r; y <= r; y++) acc += tmp[Math.clamp(y, 0, h - 1) * w + x];
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = acc * inv;
                acc += tmp[Math.min(y + r + 1, h - 1) * w + x] - tmp[Math.max(y - r, 0) * w + x];
            }
        }
        return dst;
    }

    /**
     * Tangent-space normals from a height field. {@code strength} converts a per-pixel height delta
     * (0..1 units) into a slope, i.e. depth in pixels.
     */
    static int[] normalMap(float[] h, int w, int hgt, float strength) {
        int[] out = new int[w * hgt];
        for (int y = 0; y < hgt; y++) {
            for (int x = 0; x < w; x++) {
                float dx = (h[y * w + Math.min(x + 1, w - 1)] - h[y * w + Math.max(x - 1, 0)]) * 0.5f * strength;
                float dy = (h[Math.min(y + 1, hgt - 1) * w + x] - h[Math.max(y - 1, 0) * w + x]) * 0.5f * strength;
                float nx = -dx, ny = -dy, nz = 1f;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                out[y * w + x] = argb(255, enc(nx / len), enc(ny / len), enc(nz / len));
            }
        }
        return out;
    }

    /** Dark green tray felt: fine fibre noise over soft mottling. sRGB. */
    private static int[] felt() {
        SplittableRandom rnd = new SplittableRandom(7);
        float[] mottle = valueNoise(FELT_W, FELT_H, 64, 11);
        int[] out = new int[FELT_W * FELT_H];
        for (int i = 0; i < out.length; i++) {
            float fibre = (float) rnd.nextDouble() * 0.16f - 0.08f;
            float m = 0.88f + mottle[i] * 0.2f + fibre;
            out[i] = argb(255, srgb8(0.030f * m), srgb8(0.105f * m), srgb8(0.052f * m));
        }
        return out;
    }

    /** Walnut-ish wood: warped rings running along u. sRGB. */
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
                out[i] = argb(255, srgb8(r), srgb8(gg), srgb8(b));
            }
        }
        return out;
    }

    /** Soft radial contact shadow for JavaFX, which has no shadow support. Black with alpha falloff. */
    private static int[] blobShadow() {
        int[] out = new int[SHADOW_PX * SHADOW_PX];
        for (int y = 0; y < SHADOW_PX; y++) {
            for (int x = 0; x < SHADOW_PX; x++) {
                float dx = (x + 0.5f) / SHADOW_PX * 2 - 1, dy = (y + 0.5f) / SHADOW_PX * 2 - 1;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                float a = r >= 1 ? 0 : (float) Math.pow(1 - smoothstep(0.15f, 1f, r), 1.6);
                out[y * SHADOW_PX + x] = argb(Math.round(a * 0.8f * 255), 0, 0, 0);
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
            float ty = smoothstep(0, 1, fy - y0);
            for (int x = 0; x < w; x++) {
                float fx = x / (float) cellPx;
                int x0 = (int) fx;
                float tx = smoothstep(0, 1, fx - x0);
                float a = lattice[y0 * gw + x0], b = lattice[y0 * gw + x0 + 1];
                float c = lattice[(y0 + 1) * gw + x0], d = lattice[(y0 + 1) * gw + x0 + 1];
                out[y * w + x] = (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty;
            }
        }
        return out;
    }

    /**
     * Bakes a Dice Set look into a JavaFX diffuse map: JavaFX multiplies diffuseColor by the map, so
     * two colours (die and ink) can only coexist if they are baked into the texture itself.
     */
    int[] bakeFxDiffuse(Look look) {
        int[] out = new int[ink.length];
        for (int i = 0; i < out.length; i++) {
            float k = ink[i];
            // Recess walls read darker even without real occlusion; fake it with the height field.
            float cav = 0.65f + 0.35f * height[i];
            float r = lerp(look.base()[0], look.ink()[0], k) * (k > 0.5f ? 1 : cav);
            float g = lerp(look.base()[1], look.ink()[1], k) * (k > 0.5f ? 1 : cav);
            float b = lerp(look.base()[2], look.ink()[2], k) * (k > 0.5f ? 1 : cav);
            out[i] = argb(255, srgb8(r), srgb8(g), srgb8(b));
        }
        return out;
    }

    /** JavaFX specular map: the polished face is shiny, the ink is matte. */
    int[] bakeFxSpecular(Look look) {
        int[] out = new int[ink.length];
        float face = look.fxSpecular(), inkSpec = 0.12f;
        for (int i = 0; i < out.length; i++) {
            int v = srgb8(lerp(face, inkSpec, ink[i]));
            out[i] = argb(255, v, v, v);
        }
        return out;
    }

    static int argb(int a, int r, int g, int b) {
        return (a & 0xff) << 24 | (r & 0xff) << 16 | (g & 0xff) << 8 | (b & 0xff);
    }

    private static int enc(float v) {
        return Math.round((v * 0.5f + 0.5f) * 255);
    }

    /** Linear 0..1 to sRGB-encoded 0..255. */
    static int srgb8(float linear) {
        float c = Math.clamp(linear, 0f, 1f);
        float s = c <= 0.0031308f ? c * 12.92f : (float) (1.055 * Math.pow(c, 1 / 2.4) - 0.055);
        return Math.round(s * 255);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Math.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3 - 2 * t);
    }
}
