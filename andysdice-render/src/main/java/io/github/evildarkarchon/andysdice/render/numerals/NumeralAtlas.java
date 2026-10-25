package io.github.evildarkarchon.andysdice.render.numerals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Objects;
import org.joml.Vector2dc;

/**
 * One Die Kind's numeral atlas (ADR-0008): every face's Numerals rasterised into its cell of a square image,
 * already turned into the face's frame, as an engraving profile and its slopes. Pure Java (Java2D, headless), no
 * GL; the renderer uploads it as two textures. After the numerals prototype's {@code Atlas} (render.md).
 *
 * <p>The die is dipped in paint and tumbled, so ink survives only in the recesses and the face round them is
 * polished bare. Per pixel, row 0 at the top (UV {@code v} grows image-down):
 * <ul>
 *   <li>mask, two bytes: R = ink coverage, G = engraving profile (0 = face, 1 = recess floor);</li>
 *   <li>slopes, two floats: d(profile)/d(mm) along {@code u} and along {@code v} (image-down).</li>
 * </ul>
 * The depth is not baked in: the shader multiplies the profile and slopes by the visible depth, so the depth and
 * ink fill stay uniforms.
 *
 * <p>An atlas is a large, short-lived thing (a d20's is 3200² pixels): make it, upload it, drop it. Instances are
 * not modified after construction and may be read from any thread.
 */
public final class NumeralAtlas {

    /** Pixels along each side of a face's cell (the prototype's, judged by eye at macro distance). */
    public static final int CELL_PX = 640;
    /**
     * Radius of each of the two box blurs that slope a recess wall, in mm: together they spread the wall across
     * about 4 × 0.035 = 0.14 mm (ADR-0008).
     */
    public static final double WALL_MM = 0.035;
    /** How deep the Numerals are engraved, in mm (spec §3.3, ADR-0008). */
    public static final double ENGRAVING_DEPTH_MM = 0.4;
    /**
     * The recess depth that shows above the thin film of ink on its floor, in mm: the shader's {@code uDepth}.
     * The film takes a tenth of the engraving (the numerals prototype's {@code VISIBLE_DEPTH}).
     */
    public static final double VISIBLE_DEPTH_MM = ENGRAVING_DEPTH_MM * 0.9;
    /** Profile below which paint is gone (the rim), and above which the recess is fully inked (the floor). */
    private static final float INK_FROM = 0.12f;
    private static final float INK_FULL = 0.5f;
    /** The blurred raster is stretched a little so the floor reaches a profile of exactly 1 inside strokes. */
    private static final float PROFILE_GAIN = 1.2f;

    private final NumeralLayout layout;
    private final int size;
    private final double pxPerMm;
    private final byte[] maskRg;
    private final float[] slopeRg;

    private NumeralAtlas(NumeralLayout layout, int size, double pxPerMm, byte[] maskRg, float[] slopeRg) {
        this.layout = layout;
        this.size = size;
        this.pxPerMm = pxPerMm;
        this.maskRg = maskRg;
        this.slopeRg = slopeRg;
    }

    /**
     * Rasterises a layout's Numerals at {@link #CELL_PX} pixels per cell.
     *
     * @param layout the Die Kind's layout
     * @return a new atlas
     */
    public static NumeralAtlas rasterise(NumeralLayout layout) {
        Objects.requireNonNull(layout, "layout");
        int size = CELL_PX * layout.grid();
        double pxPerMm = CELL_PX / (2 * layout.cellHalfMm());
        float[] profile = glyphs(layout, size, pxPerMm);
        int blur = Math.max(1, (int) Math.round(WALL_MM * pxPerMm));
        profile = boxBlur(boxBlur(profile, size, blur), size, blur);
        byte[] mask = new byte[size * size * 2];
        for (int i = 0; i < profile.length; i++) {
            float p = Math.min(1f, profile[i] * PROFILE_GAIN);
            profile[i] = p;
            // Paint coats the floor and the lower walls; tumbling strips it from the rim and the face.
            float ink = smoothstep(INK_FROM, INK_FULL, p);
            mask[i * 2] = (byte) Math.round(ink * 255);
            mask[i * 2 + 1] = (byte) Math.round(p * 255);
        }
        float[] slope = new float[size * size * 2];
        float scale = (float) (0.5 * pxPerMm);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int i = y * size + x;
                // Central differences, clamped at the image edge (which is blank anyway).
                slope[i * 2] = (profile[y * size + Math.min(x + 1, size - 1)]
                        - profile[y * size + Math.max(x - 1, 0)]) * scale;
                slope[i * 2 + 1] = (profile[Math.min(y + 1, size - 1) * size + x]
                        - profile[Math.max(y - 1, 0) * size + x]) * scale;
            }
        }
        return new NumeralAtlas(layout, size, pxPerMm, mask, slope);
    }

    /** Draws every placement into its face's cell, white on black, antialiased. */
    private static float[] glyphs(NumeralLayout layout, int size, double pxPerMm) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, size, size);
            g.setColor(Color.WHITE);
            for (NumeralLayout.Face face : layout.faces()) {
                int cell = layout.cell(face.index());
                int column = cell % layout.grid();
                int row = cell / layout.grid();
                for (NumeralLayout.Placement p : face.placements()) {
                    // Face frame (mm, y up) -> cell pixels (y down), centred on the cell: the atlas mapping.
                    AffineTransform t = new AffineTransform();
                    t.translate((column + 0.5) * CELL_PX, (row + 0.5) * CELL_PX);
                    t.scale(pxPerMm, -pxPerMm);
                    g.fill(t.createTransformedShape(p.outline()));
                }
            }
        } finally {
            g.dispose();
        }
        byte[] px = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        float[] out = new float[px.length];
        for (int i = 0; i < px.length; i++) {
            out[i] = (px[i] & 0xff) / 255f;
        }
        return out;
    }

    /** A separable box blur of radius {@code r}, edges clamped. */
    private static float[] boxBlur(float[] src, int n, int r) {
        float[] tmp = new float[src.length];
        float[] dst = new float[src.length];
        float inv = 1f / (2 * r + 1);
        for (int y = 0; y < n; y++) {
            float acc = 0;
            for (int x = -r; x <= r; x++) {
                acc += src[y * n + Math.clamp(x, 0, n - 1)];
            }
            for (int x = 0; x < n; x++) {
                tmp[y * n + x] = acc * inv;
                acc += src[y * n + Math.min(x + r + 1, n - 1)] - src[y * n + Math.max(x - r, 0)];
            }
        }
        for (int x = 0; x < n; x++) {
            float acc = 0;
            for (int y = -r; y <= r; y++) {
                acc += tmp[Math.clamp(y, 0, n - 1) * n + x];
            }
            for (int y = 0; y < n; y++) {
                dst[y * n + x] = acc * inv;
                acc += tmp[Math.min(y + r + 1, n - 1) * n + x] - tmp[Math.max(y - r, 0) * n + x];
            }
        }
        return dst;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3 - 2 * t);
    }

    /** @return the layout rasterised, whose mapping gives each face's UVs */
    public NumeralLayout layout() {
        return layout;
    }

    /** @return the pixels along each side of the square image */
    public int sizePx() {
        return size;
    }

    /** @return pixels per mm on a face */
    public double pxPerMm() {
        return pxPerMm;
    }

    /**
     * The engraving profile at a pixel.
     *
     * @param x the column, from the left
     * @param y the row, from the top
     * @return 0 on the face, 1 on the recess floor, between on the walls
     */
    public double profile(int x, int y) {
        return (maskRg[index(x, y) * 2 + 1] & 0xff) / 255.0;
    }

    /**
     * The ink coverage at a pixel.
     *
     * @param x the column, from the left
     * @param y the row, from the top
     * @return 0 bare, 1 fully inked
     */
    public double ink(int x, int y) {
        return (maskRg[index(x, y) * 2] & 0xff) / 255.0;
    }

    /**
     * The profile's slope along {@code u} at a pixel.
     *
     * @param x the column, from the left
     * @param y the row, from the top
     * @return d(profile)/d(mm), rightward
     */
    public double slopeU(int x, int y) {
        return slopeRg[index(x, y) * 2];
    }

    /**
     * The profile's slope along {@code v} at a pixel.
     *
     * @param x the column, from the left
     * @param y the row, from the top
     * @return d(profile)/d(mm), image-down
     */
    public double slopeV(int x, int y) {
        return slopeRg[index(x, y) * 2 + 1];
    }

    /**
     * The profile at an atlas UV, from the nearest pixel.
     *
     * @param u across, 0..1
     * @param v down, 0..1
     * @return the profile there; 0 off the image
     */
    public double profileAt(double u, double v) {
        int x = (int) Math.floor(u * size);
        int y = (int) Math.floor(v * size);
        if (x < 0 || y < 0 || x >= size || y >= size) {
            return 0;
        }
        return profile(x, y);
    }

    /**
     * Copies the mask into a buffer for upload as {@code GL_RG8}: two bytes per pixel, top row first.
     *
     * @param dest a buffer with at least {@code 2 × sizePx()²} bytes remaining; its position advances
     * @return {@code dest}
     */
    public ByteBuffer putMask(ByteBuffer dest) {
        return dest.put(maskRg);
    }

    /**
     * Copies the slopes into a buffer for upload as {@code GL_RG16F}: two floats per pixel, top row first.
     *
     * @param dest a buffer with at least {@code 2 × sizePx()²} floats remaining; its position advances
     * @return {@code dest}
     */
    public FloatBuffer putSlopes(FloatBuffer dest) {
        return dest.put(slopeRg);
    }

    /**
     * A picture of the atlas for the dev gallery: every face's flat polygon in ivory on the darker blank surface,
     * its recesses shaded by depth and its ink in near-black, scaled down to fit.
     *
     * @param maxPx the largest side the picture may have
     * @return a new opaque image of {@code min(sizePx(), maxPx)} pixels square
     */
    public BufferedImage sheet(int maxPx) {
        int out = Math.min(size, maxPx);
        double scale = out / (double) size;
        BufferedImage img = new BufferedImage(out, out, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x9c9484));
            g.fillRect(0, 0, out, out);
            g.setColor(new Color(0xe8e0cc));
            for (NumeralLayout.Face face : layout.faces()) {
                int cell = layout.cell(face.index());
                Path2D polygon = new Path2D.Double();
                for (Vector2dc p : face.polygon()) {
                    double x = ((cell % layout.grid() + 0.5) * CELL_PX + p.x() * pxPerMm) * scale;
                    double y = ((cell / layout.grid() + 0.5) * CELL_PX - p.y() * pxPerMm) * scale;
                    if (polygon.getCurrentPoint() == null) {
                        polygon.moveTo(x, y);
                    } else {
                        polygon.lineTo(x, y);
                    }
                }
                polygon.closePath();
                g.fill(polygon);
            }
        } finally {
            g.dispose();
        }
        int ink = 0x1e1e1e;
        for (int y = 0; y < out; y++) {
            int y0 = (int) (y / scale);
            int y1 = Math.max(y0 + 1, (int) ((y + 1) / scale));
            for (int x = 0; x < out; x++) {
                int x0 = (int) (x / scale);
                int x1 = Math.max(x0 + 1, (int) ((x + 1) / scale));
                // Box-average the source block under this pixel.
                double p = 0;
                double k = 0;
                for (int sy = y0; sy < Math.min(y1, size); sy++) {
                    for (int sx = x0; sx < Math.min(x1, size); sx++) {
                        p += profile(sx, sy);
                        k += ink(sx, sy);
                    }
                }
                int n = (Math.min(y1, size) - y0) * (Math.min(x1, size) - x0);
                p /= n;
                k /= n;
                if (p == 0) {
                    continue;
                }
                int rgb = img.getRGB(x, y);
                int r = mixChannel((rgb >> 16) & 0xff, (ink >> 16) & 0xff, p, k);
                int gr = mixChannel((rgb >> 8) & 0xff, (ink >> 8) & 0xff, p, k);
                int b = mixChannel(rgb & 0xff, ink & 0xff, p, k);
                img.setRGB(x, y, (r << 16) | (gr << 8) | b);
            }
        }
        return img;
    }

    /** A channel darkened by the recess's depth, then painted over by its ink. */
    private static int mixChannel(int surface, int ink, double profile, double coverage) {
        double shaded = surface * (1 - 0.3 * profile);
        return (int) Math.round(shaded + (ink - shaded) * coverage);
    }

    private int index(int x, int y) {
        Objects.checkIndex(x, size);
        Objects.checkIndex(y, size);
        return y * size + x;
    }
}
