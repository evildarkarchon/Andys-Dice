package andysdice.prototype.numerals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * PROTOTYPE. The per-kind numeral atlas: one cell per face, numerals drawn already rotated into the
 * face's frame. Follows the research on how dice are made (issue #5, §4): numerals are moulded
 * recesses, the die is dipped in paint and tumbled, so ink survives only inside the recesses and the
 * face around them is polished bare.
 *
 * <ul>
 *   <li>{@link #maskRg}: R = ink coverage, G = engraving profile (0 = face, 1 = recess floor).</li>
 *   <li>{@link #slopeRg}: d(profile)/d(mm) along the tangent (u) and bitangent (v, image-down).</li>
 * </ul>
 *
 * <p>Depth is not baked in: the shader multiplies the profile and slopes by the visible depth, so the
 * depth and ink-fill knobs are free.
 */
final class Atlas {
    static final int CELL_PX = Integer.getInteger("numerals.cellPx", 640);
    /** Half-width of the sloped recess wall, in mm (two box blurs of this radius). */
    static final float WALL_MM = 0.035f;

    final int size;
    final byte[] maskRg;
    final float[] slopeRg;

    Atlas(DieModel model) {
        size = CELL_PX * model.grid;
        float pxPerMm = CELL_PX / (2 * model.cellHalfMm);
        float[] glyphs = rasterise(model, size, pxPerMm);
        int blur = Math.max(1, Math.round(WALL_MM * pxPerMm));
        float[] profile = boxBlur(boxBlur(glyphs, size, blur), size, blur);
        maskRg = new byte[size * size * 2];
        for (int i = 0; i < profile.length; i++) {
            float p = Math.min(1f, profile[i] * 1.2f);
            profile[i] = p;
            // Paint coats the floor and the lower walls; tumbling strips it from the rim and the face.
            float ink = smoothstep(0.12f, 0.5f, p);
            maskRg[i * 2] = (byte) Math.round(ink * 255);
            maskRg[i * 2 + 1] = (byte) Math.round(p * 255);
        }
        slopeRg = new float[size * size * 2];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int i = y * size + x;
                float dx = (profile[y * size + Math.min(x + 1, size - 1)] - profile[y * size + Math.max(x - 1, 0)]) * 0.5f * pxPerMm;
                float dy = (profile[Math.min(y + 1, size - 1) * size + x] - profile[Math.max(y - 1, 0) * size + x]) * 0.5f * pxPerMm;
                slopeRg[i * 2] = dx;
                slopeRg[i * 2 + 1] = dy;
            }
        }
    }

    /** Draws every placement into its cell, white on black. */
    private static float[] rasterise(DieModel model, int size, float pxPerMm) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, size, size);
        g.setColor(Color.WHITE);
        for (DieModel.FaceLayout l : model.layouts) {
            int cx = l.cell % model.grid, cy = l.cell / model.grid;
            for (DieModel.Placement p : l.placements) {
                Rectangle2D b = p.glyph().getBounds2D();
                // em (y down) -> numeral frame (y up) -> face frame (rotated) -> cell px (y down).
                AffineTransform t = new AffineTransform();
                t.translate((cx + 0.5) * CELL_PX, (cy + 0.5) * CELL_PX);
                t.scale(pxPerMm, -pxPerMm);
                t.translate(p.s(), p.t());
                t.rotate(p.angle());
                t.scale(p.sigma(), -p.sigma());
                t.translate(-b.getCenterX(), -b.getCenterY());
                g.fill(t.createTransformedShape(p.glyph()));
            }
        }
        g.dispose();
        byte[] px = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        float[] out = new float[px.length];
        for (int i = 0; i < px.length; i++) out[i] = (px[i] & 0xff) / 255f;
        return out;
    }

    private static float[] boxBlur(float[] src, int n, int r) {
        float[] tmp = new float[src.length], dst = new float[src.length];
        float inv = 1f / (2 * r + 1);
        for (int y = 0; y < n; y++) {
            float acc = 0;
            for (int x = -r; x <= r; x++) acc += src[y * n + Math.clamp(x, 0, n - 1)];
            for (int x = 0; x < n; x++) {
                tmp[y * n + x] = acc * inv;
                acc += src[y * n + Math.min(x + r + 1, n - 1)] - src[y * n + Math.max(x - r, 0)];
            }
        }
        for (int x = 0; x < n; x++) {
            float acc = 0;
            for (int y = -r; y <= r; y++) acc += tmp[Math.clamp(y, 0, n - 1) * n + x];
            for (int y = 0; y < n; y++) {
                dst[y * n + x] = acc * inv;
                acc += tmp[Math.min(y + r + 1, n - 1) * n + x] - tmp[Math.max(y - r, 0) * n + x];
            }
        }
        return dst;
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Math.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3 - 2 * t);
    }
}
