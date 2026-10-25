package io.github.evildarkarchon.andysdice.render.numerals;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Vector2d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Reads the Numerals a face shows back out of a rasterised atlas, the way an eye would: it samples the face
 * through the atlas mapping, then finds which Numeral of the Die Kind, drawn by the font at the Appendix A height,
 * best matches the engraved shape at any angle. Nothing about where the layout put a Numeral is used, so the
 * reading checks the layout and the mapping together.
 *
 * <p>The face is sampled in a frame of its own (any in-plane axes), and the matched angle turned back into a 3D
 * direction for the Numeral's top.
 */
final class AtlasReader {

    /** What a face (or, on the d4, one corner of a face) shows. */
    record Reading(String numeral, boolean underlined, Vector3dc centre, Vector3dc up, double match) {}

    private static final double ANGLE_STEP = Math.toRadians(5);
    /** Template rasters, in samples per cap height. */
    private static final int TEMPLATE_PX_PER_EM = 200;

    private final NumeralAtlas atlas;
    private final DieKind kind;
    private final double heightMm;
    private final double step;
    private final List<Template> templates = new ArrayList<>();

    /**
     * @param atlas the atlas to read
     * @param heightMm the cap height to match at: the spec's, not the layout's
     */
    AtlasReader(NumeralAtlas atlas, double heightMm) {
        this.atlas = atlas;
        this.kind = atlas.layout().kind();
        this.heightMm = heightMm;
        this.step = heightMm / 24;
        Set<String> numerals = new LinkedHashSet<>();
        for (int f = 0; f < kind.faceCount(); f++) {
            numerals.add(kind.numeral(f));
        }
        for (String n : numerals) {
            templates.add(new Template(n, false));
            templates.add(new Template(n, true));
        }
    }

    /**
     * Reads a face.
     *
     * @param face the face-table index
     * @return one reading, or on the d4 one per corner the face carries
     */
    List<Reading> read(int face) {
        Vector3dc n = kind.solid().faces().get(face).normal();
        // Sampling axes of the test's own choosing, right-handed seen from outside: e1 × e2 = n.
        Vector3d e1 = n.cross(new Vector3d(0.31, 0.53, 0.79), new Vector3d()).normalize();
        Vector3d e2 = n.cross(e1, new Vector3d()).normalize();
        List<Vector3dc> ring = new ArrayList<>();
        Vector3d centre = new Vector3d();
        for (int v : kind.solid().faces().get(face).ring()) {
            ring.add(kind.solid().vertices().get(v));
            centre.add(kind.solid().vertices().get(v));
        }
        centre.div(ring.size());
        double reach = 0;
        for (Vector3dc v : ring) {
            reach = Math.max(reach, v.distance(centre));
        }
        // Ink samples, as (x, y) along (e1, e2), grouped by region: the whole face, or the nearest d4 corner.
        Map<Integer, List<double[]>> regions = new HashMap<>();
        int cell = atlas.layout().cell(face);
        Vector2d uv = new Vector2d();
        for (double y = -reach; y <= reach; y += step) {
            for (double x = -reach; x <= reach; x += step) {
                Vector3d p = new Vector3d(centre).fma(x, e1).fma(y, e2);
                atlas.layout().uv(face, p, uv);
                if (cellOf(uv) != cell || atlas.profileAt(uv.x, uv.y) < 0.5) {
                    continue;
                }
                int region = 0;
                if (kind == DieKind.D4) {
                    double best = Double.NEGATIVE_INFINITY;
                    for (int k = 0; k < ring.size(); k++) {
                        double closeness = -ring.get(k).distance(p);
                        if (closeness > best) {
                            best = closeness;
                            region = k;
                        }
                    }
                }
                regions.computeIfAbsent(region, r -> new ArrayList<>()).add(new double[] {x, y});
            }
        }
        List<Reading> out = new ArrayList<>();
        for (List<double[]> ink : regions.values()) {
            out.add(match(ink, centre, e1, e2));
        }
        return out;
    }

    private int cellOf(Vector2d uv) {
        int grid = atlas.layout().grid();
        if (uv.x < 0 || uv.x >= 1 || uv.y < 0 || uv.y >= 1) {
            return -1;
        }
        return (int) (uv.y * grid) * grid + (int) (uv.x * grid);
    }

    /** The best template, at the best angle, centred on the ink's centroid. */
    private Reading match(List<double[]> ink, Vector3dc centre, Vector3dc e1, Vector3dc e2) {
        double cx = 0;
        double cy = 0;
        for (double[] p : ink) {
            cx += p[0];
            cy += p[1];
        }
        cx /= ink.size();
        cy /= ink.size();
        Template bestTemplate = null;
        double bestAngle = 0;
        double best = -1;
        double inkArea = ink.size() * step * step;
        for (Template t : templates) {
            double templateArea = t.area * heightMm * heightMm;
            for (double angle = 0; angle < 2 * Math.PI - 1e-9; angle += ANGLE_STEP) {
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                int both = 0;
                for (double[] p : ink) {
                    double dx = p[0] - cx;
                    double dy = p[1] - cy;
                    // Into the Numeral's frame: its right is e1 turned by angle, its up e2 turned by angle.
                    double a = dx * cos + dy * sin;
                    double b = -dx * sin + dy * cos;
                    if (t.contains(t.cx + a / heightMm, t.cy - b / heightMm)) {
                        both++;
                    }
                }
                double intersection = both * step * step;
                double iou = intersection / (inkArea + templateArea - intersection);
                if (iou > best) {
                    best = iou;
                    bestTemplate = t;
                    bestAngle = angle;
                }
            }
        }
        Vector3d up = new Vector3d(e2).mul(Math.cos(bestAngle)).fma(-Math.sin(bestAngle), e1);
        Vector3d at = new Vector3d(centre).fma(cx, e1).fma(cy, e2);
        return new Reading(bestTemplate.numeral, bestTemplate.underlined, at, up, best);
    }

    /** A Numeral drawn by the font, as a fine bitmap in glyph space, with its centroid and area. */
    private static final class Template {
        final String numeral;
        final boolean underlined;
        final boolean[] bits;
        final int width;
        final int height;
        final double x0;
        final double y0;
        final double cx;
        final double cy;
        final double area;

        Template(String numeral, boolean underlined) {
            this.numeral = numeral;
            this.underlined = underlined;
            Area glyph = StrokeFont.CHESSEX.text(numeral, underlined);
            Rectangle2D b = glyph.getBounds2D();
            x0 = b.getMinX();
            y0 = b.getMinY();
            width = (int) Math.ceil(b.getWidth() * TEMPLATE_PX_PER_EM) + 1;
            height = (int) Math.ceil(b.getHeight() * TEMPLATE_PX_PER_EM) + 1;
            bits = new boolean[width * height];
            double sx = 0;
            double sy = 0;
            int count = 0;
            for (int j = 0; j < height; j++) {
                for (int i = 0; i < width; i++) {
                    double x = x0 + (i + 0.5) / TEMPLATE_PX_PER_EM;
                    double y = y0 + (j + 0.5) / TEMPLATE_PX_PER_EM;
                    if (glyph.contains(x, y)) {
                        bits[j * width + i] = true;
                        sx += x;
                        sy += y;
                        count++;
                    }
                }
            }
            cx = sx / count;
            cy = sy / count;
            area = count / (double) (TEMPLATE_PX_PER_EM * TEMPLATE_PX_PER_EM);
        }

        boolean contains(double x, double y) {
            int i = (int) Math.floor((x - x0) * TEMPLATE_PX_PER_EM);
            int j = (int) Math.floor((y - y0) * TEMPLATE_PX_PER_EM);
            return i >= 0 && j >= 0 && i < width && j < height && bits[j * width + i];
        }
    }
}
