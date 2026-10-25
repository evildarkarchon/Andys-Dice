package io.github.evildarkarchon.andysdice.render.numerals;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/**
 * The procedural numeral font of ADR-0008: every digit is a few centre-line strokes (lines, elliptical arcs,
 * cubic curves) stroked with a round-jointed pen and unioned into one filled outline. No font files. Ported from
 * the numerals prototype's {@code StrokeFont} (its Chessex style only, the one ADR-0008 chose).
 *
 * <p>Glyph space is y-down with the cap height spanning y = 0..1, so the baseline is y = 1 and one unit is one
 * cap height. Each stroke's centre line is inset by half the stroke weight, so the filled outline spans exactly
 * the glyph box.
 *
 * <p>Pure Java2D geometry, no GL and no rasterising; instances are immutable and safe to share.
 */
final class StrokeFont {

    /**
     * Chessex's marks from the research: a running-track 0, a 1 with an upstroke, a 7 with a hook on its bar;
     * bold (0.16 cap heights) and condensed (0.56 cap heights wide, the 1 0.36).
     */
    static final StrokeFont CHESSEX = new StrokeFont(0.16f, 0.56f, 0.36f);

    /** Gap between glyph boxes in a multi-digit Numeral, in cap heights. */
    static final float TRACKING = 0.06f;

    /** Stroke weight in cap heights. */
    private final float weight;
    /** Width of every glyph box but the 1's, in cap heights. */
    private final float width;
    /** Width of the 1's glyph box, in cap heights. */
    private final float widthOfOne;

    private StrokeFont(float weight, float width, float widthOfOne) {
        this.weight = weight;
        this.width = width;
        this.widthOfOne = widthOfOne;
    }

    /** @return the stroke weight, in cap heights */
    float weight() {
        return weight;
    }

    /**
     * The filled outline of a Numeral. The origin is the top-left of the first glyph box.
     *
     * @param digits the Numeral, one or more of {@code 0}..{@code 9}
     * @param underlined whether to draw the bar that tells a lone 6 or 9 from its upside-down twin: a separate
     *     bar below the baseline under most of the Numeral's width
     * @return a new outline, in glyph space
     * @throws IllegalArgumentException if {@code digits} is empty or holds anything but digits
     */
    Area text(String digits, boolean underlined) {
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("no digits");
        }
        Area out = new Area();
        float x = 0;
        for (char ch : digits.toCharArray()) {
            float w = ch == '1' ? widthOfOne : width;
            Area g = glyph(ch, w);
            g.transform(AffineTransform.getTranslateInstance(x, 0));
            out.add(g);
            x += w + TRACKING;
        }
        if (underlined) {
            Rectangle2D b = out.getBounds2D();
            double h = weight * 0.72;
            double gap = weight * 0.55;
            out.add(new Area(new Rectangle2D.Double(b.getX() + b.getWidth() * 0.06, 1 + gap, b.getWidth() * 0.88, h)));
        }
        return out;
    }

    /** One digit's outline in a box {@code w} wide; the strokes' coordinates are the prototype's, tuned by eye. */
    private Area glyph(char ch, float w) {
        float s = weight;
        float h = s / 2;
        float xl = h;
        float xr = w - h;
        float yt = h;
        float yb = 1 - h;
        float xm = w / 2;
        Path2D p = new Path2D.Float();
        Area extra = null;
        switch (ch) {
            case '0' -> {
                // Running track: two straight sides joined by semicircles.
                float r = (xr - xl) / 2;
                p.append(new Arc2D.Float(xl, yt, 2 * r, 2 * r, 0, 180, Arc2D.OPEN), false);
                p.lineTo(xl, yb - r);
                p.append(new Arc2D.Float(xl, yb - 2 * r, 2 * r, 2 * r, 180, 180, Arc2D.OPEN), true);
                p.closePath();
            }
            case '1' -> {
                float stem = xr - 0.02f;
                // Butt caps: start the flag on the far side and run the stem to the exact top and bottom.
                p.moveTo(xl, 0.34f);
                p.lineTo(stem, yt);
                p.lineTo(stem, 1f);
            }
            case '2' -> {
                float bowlH = (xr - xl) * 0.92f;
                p.append(new Arc2D.Float(xl, yt, xr - xl, bowlH, 165, -165, Arc2D.OPEN), false);
                p.curveTo(xr, yt + bowlH * 0.5f + 0.16f, xl + 0.12f, 0.70f, xl, yb);
                p.lineTo(w, yb);
            }
            case '3' -> {
                float mid = 0.47f;
                p.append(new Arc2D.Float(xl + 0.03f, yt, xr - xl - 0.04f, mid - yt, 155, -245, Arc2D.OPEN), false);
                p.append(new Arc2D.Float(xl, mid, xr - xl, yb - mid, 90, -250, Arc2D.OPEN), true);
            }
            case '4' -> {
                float bar = 0.70f;
                float stem = xr - 0.07f;
                p.moveTo(stem, 1f);
                p.lineTo(stem, yt);
                p.lineTo(xl, bar);
                p.lineTo(w, bar);
            }
            case '5' -> {
                float bowlTop = 0.36f;
                p.moveTo(w, yt);
                p.lineTo(xl + 0.05f, yt);
                p.lineTo(xl + 0.02f, 0.47f);
                p.append(new Arc2D.Float(xl - 0.01f, bowlTop, xr - xl + 0.01f, yb - bowlTop, 140, -282, Arc2D.OPEN),
                        true);
            }
            case '6', '9' -> {
                float bowlTop = 0.40f;
                p.append(new Ellipse2D.Float(xl, bowlTop, xr - xl, yb - bowlTop), false);
                Path2D stem = new Path2D.Float();
                float cy = (bowlTop + yb) / 2;
                stem.moveTo(xr - 0.03f, yt + 0.07f);
                stem.curveTo(xr - 0.14f, yt - 0.01f, xl, yt + 0.04f, xl, cy);
                extra = stroke(stem);
            }
            case '7' -> {
                p.moveTo(xl, yt + 0.17f); // the hook on the bar
                p.lineTo(xl, yt);
                p.lineTo(xr, yt);
                p.quadTo(xr - 0.20f, 0.50f, xm - 0.07f, 1f);
            }
            case '8' -> {
                float waist = 0.47f;
                p.append(new Ellipse2D.Float(xl + 0.035f, yt, xr - xl - 0.07f, waist + 0.01f - yt), false);
                p.append(new Ellipse2D.Float(xl, waist - 0.01f, xr - xl, yb - waist + 0.01f), false);
            }
            default -> throw new IllegalArgumentException("not a digit: '" + ch + "'");
        }
        Area a = stroke(p);
        if (extra != null) {
            a.add(extra);
        }
        if (ch == '9') {
            // A 9 is a 6 turned upside down, which is exactly why dice mark them.
            a.transform(AffineTransform.getRotateInstance(Math.PI, w / 2, 0.5));
        }
        return a;
    }

    /** A centre line stroked with this font's pen: butt caps, round joins. */
    private Area stroke(Shape centreLine) {
        return new Area(new BasicStroke(weight, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
                .createStrokedShape(centreLine));
    }
}
