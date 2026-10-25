package andysdice.prototype.tray;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/**
 * PROTOTYPE. A procedural numeral font: every digit is a few centre-line strokes (lines, elliptical
 * arcs, cubic curves) stroked with a round-jointed pen and unioned into one filled outline. No font
 * files and no external art tools, as the map's Notes require.
 *
 * <p>Glyph space is y-down with the cap height spanning y = 0..1 (the baseline is y = 1). Each
 * stroke's centre line is inset by half the stroke weight so the filled outline spans exactly the
 * glyph box.
 */
final class StrokeFont {
    /** A font style: the shapes that differ, plus proportions. */
    enum Style {
        /**
         * Chessex's marks from the research (issue #5, §4): a "running-track" 0, a 1 with an upstroke,
         * a 7 with a hook on its bar; bold and condensed.
         */
        CHESSEX("Chessex-style (track 0, hooked 7, flag 1)", 0.56f),
        /** A plainer, wider comparison: oval 0, straight 7, 1 with a base bar (Koplow-like). */
        PLAIN("Plain (oval 0, straight 7, based 1)", 0.64f);

        final String label;
        final float width;

        Style(String label, float width) {
            this.label = label;
            this.width = width;
        }
    }

    /** How a 6 or 9 is told apart from its upside-down twin. */
    enum Mark {
        UNDERLINE("underline (Chessex)"), DOT("dot (Koplow, D&G)"), NONE("none");

        final String label;

        Mark(String label) {
            this.label = label;
        }
    }

    /** Gap between glyph boxes in a multi-digit numeral, in cap heights. */
    private static final float TRACKING = 0.06f;

    final Style style;
    /** Stroke weight in cap heights. */
    final float weight;

    StrokeFont(Style style, float weight) {
        this.style = style;
        this.weight = weight;
    }

    /**
     * The filled outline of {@code text} (digits only), with the 6/9 mark applied when
     * {@code marked}. The origin is the top-left of the first glyph box.
     */
    Area text(String text, boolean marked, Mark mark) {
        Area out = new Area();
        float x = 0;
        for (char ch : text.toCharArray()) {
            float w = ch == '1' ? glyphWidth1() : style.width;
            Area g = glyph(ch, w);
            g.transform(AffineTransform.getTranslateInstance(x, 0));
            out.add(g);
            x += w + TRACKING;
        }
        if (marked && mark != Mark.NONE) {
            Rectangle2D b = out.getBounds2D();
            if (mark == Mark.UNDERLINE) {
                double h = weight * 0.72, gap = weight * 0.55;
                out.add(new Area(new Rectangle2D.Double(b.getX() + b.getWidth() * 0.06, 1 + gap, b.getWidth() * 0.88, h)));
            } else {
                double d = weight * 1.15;
                out.add(new Area(new Ellipse2D.Double(b.getMaxX() + weight * 0.45, 1 - d, d, d)));
            }
        }
        return out;
    }

    private float glyphWidth1() {
        return style == Style.PLAIN ? 0.44f : 0.36f;
    }

    private Area glyph(char ch, float w) {
        float s = weight, h = s / 2;
        float xl = h, xr = w - h, yt = h, yb = 1 - h, xm = w / 2;
        Path2D p = new Path2D.Float();
        Area extra = null;
        switch (ch) {
            case '0' -> {
                if (style == Style.CHESSEX) {
                    // Running track: two straight sides joined by semicircles.
                    float r = (xr - xl) / 2;
                    p.append(new Arc2D.Float(xl, yt, 2 * r, 2 * r, 0, 180, Arc2D.OPEN), false);
                    p.lineTo(xl, yb - r);
                    p.append(new Arc2D.Float(xl, yb - 2 * r, 2 * r, 2 * r, 180, 180, Arc2D.OPEN), true);
                    p.closePath();
                } else {
                    p.append(new Ellipse2D.Float(xl, yt, xr - xl, yb - yt), false);
                }
            }
            case '1' -> {
                float stem = xr - 0.02f;
                // Butt caps: start the flag on the far side and run the stem to the exact top/bottom.
                p.moveTo(xl, style == Style.CHESSEX ? 0.34f : 0.26f);
                p.lineTo(stem, yt);
                p.lineTo(stem, style == Style.PLAIN ? yb : 1f);
                if (style == Style.PLAIN) {
                    Path2D base = new Path2D.Float();
                    base.moveTo(0, yb);
                    base.lineTo(w + 0.04f, yb);
                    extra = stroke(base);
                }
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
                float bar = 0.70f, stem = xr - 0.07f;
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
                p.append(new Arc2D.Float(xl - 0.01f, bowlTop, xr - xl + 0.01f, yb - bowlTop, 140, -282, Arc2D.OPEN), true);
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
                if (style == Style.CHESSEX) {
                    p.moveTo(xl, yt + 0.17f); // the hook on the bar
                    p.lineTo(xl, yt);
                } else {
                    p.moveTo(0, yt);
                }
                p.lineTo(xr, yt);
                p.quadTo(xr - 0.20f, 0.50f, xm - 0.07f, 1f);
            }
            case '8' -> {
                float waist = 0.47f;
                p.append(new Ellipse2D.Float(xl + 0.035f, yt, xr - xl - 0.07f, waist + 0.01f - yt), false);
                p.append(new Ellipse2D.Float(xl, waist - 0.01f, xr - xl, yb - waist + 0.01f), false);
            }
            default -> throw new IllegalArgumentException("not a digit: " + ch);
        }
        Area a = stroke(p);
        if (extra != null) a.add(extra);
        if (ch == '9') {
            // A 9 is a 6 turned upside down, which is exactly why dice mark them.
            a.transform(AffineTransform.getRotateInstance(Math.PI, w / 2, 0.5));
        }
        return a;
    }

    private Area stroke(Shape centreLine) {
        return new Area(new BasicStroke(weight, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND).createStrokedShape(centreLine));
    }
}
