package io.github.evildarkarchon.andysdice.render.numerals;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.Solid;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.joml.Vector2d;
import org.joml.Vector2dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Where every Numeral of a Die Kind sits (ADR-0008): each flat face's 2D frame, the Numerals placed in it, their
 * shared height, and the mapping from a point on a face to its cell of the Die Kind's atlas. Pure Java, no GL;
 * after the numerals prototype's {@code DieModel} (render.md, geometry.md), in doubles over {@code core}'s solids.
 *
 * <p>Each face's frame has its origin at the flat face's centroid, {@code up} along the Numeral's top and
 * {@code right = up × n}, so it reads upright seen from outside. The flat face is the rounded solid's: the kernel
 * face (at {@code inradius - edgeRadius}) pushed out by the edge radius, so it lies on the sharp solid's face
 * plane, as {@link io.github.evildarkarchon.andysdice.render.mesh.DieMesh} builds it.
 *
 * <p>Atlas mapping: face {@code i} of the face table owns cell {@code i} of a {@link #grid()} × {@code grid()}
 * atlas, and its last cell is blank Die surface for the fillets and caps. A face-frame point {@code (s, t)} in mm
 * maps to {@code u = (column + 1/2 + s / cellSize) / grid}, {@code v = (row + 1/2 - t / cellSize) / grid}, with
 * {@code v} growing image-down. The Numerals are drawn into the cell already turned into the face's frame
 * ({@link NumeralAtlas}), so a face's UVs are a plain projection.
 *
 * <p>Instances are immutable and shared; {@link #of} builds each once.
 */
public final class NumeralLayout {

    /** Clearance between a Numeral and the start of the edge fillet, in mm (ADR-0008). */
    public static final double MARGIN_MM = 0.45;
    /** The share of the largest height that fits every face that the Numerals are drawn at (ADR-0008). */
    public static final double SIZE_FRACTION = 0.92;
    /** A Numeral's height never exceeds this fraction of the flat face's inscribed-circle diameter (ADR-0008). */
    public static final double CAP_OF_INSCRIBED = 0.80;
    /** On the d4 each corner Numeral stays within this fraction of the way from its corner to the far edge. */
    public static final double D4_CORNER_FRACTION = 0.5;
    /** Room round the largest flat face inside an atlas cell, in mm, so no Numeral or face touches its border. */
    private static final double CELL_PAD_MM = 0.15;

    private static final StrokeFont FONT = StrokeFont.CHESSEX;

    /**
     * One numbered flat face of the rounded solid, in the z-up model frame.
     *
     * @param index the face-table index, which is also its atlas cell
     * @param normal the unit outward normal
     * @param centre the flat face's centroid, in mm
     * @param up the face frame's up, a unit vector in the face plane: the top of an unturned Numeral
     * @param right {@code up × normal}, the face frame's other axis
     * @param polygon the flat face in the frame, {@code (s, t)} = (along right, along up) in mm, counter-clockwise
     * @param placements the Numerals on the face: one, or on the d4 three
     */
    public record Face(int index, Vector3dc normal, Vector3dc centre, Vector3dc up, Vector3dc right,
                       List<Vector2dc> polygon, List<Placement> placements) {

        /** Copies the parts, so that a face cannot change once built. */
        public Face {
            normal = new Vector3d(normal);
            centre = new Vector3d(centre);
            up = new Vector3d(up);
            right = new Vector3d(right);
            polygon = polygon.stream().<Vector2dc>map(Vector2d::new).toList();
            placements = List.copyOf(placements);
        }
    }

    /**
     * One Numeral on a face. The glyph's bounding box (underline included) is centred on {@code (s, t)} in the
     * face frame and turned counter-clockwise by {@code angle} from the frame's up.
     */
    public static final class Placement {
        private final String numeral;
        private final boolean underlined;
        private final double heightMm;
        private final double s;
        private final double t;
        private final double angle;
        private final Vector3dc centre;
        private final Vector3dc up;
        private final Vector3dc right;
        private final Area glyph;

        private Placement(String numeral, boolean underlined, double heightMm, double s, double t, double angle,
                          Face frame) {
            this.numeral = numeral;
            this.underlined = underlined;
            this.heightMm = heightMm;
            this.s = s;
            this.t = t;
            this.angle = angle;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            centre = new Vector3d(frame.centre()).fma(s, frame.right()).fma(t, frame.up());
            // Turning the frame's up counter-clockwise (seen from outside) by angle.
            up = new Vector3d(frame.up()).mul(cos).fma(-sin, frame.right());
            right = new Vector3d(frame.right()).mul(cos).fma(sin, frame.up());
            glyph = FONT.text(numeral, underlined);
        }

        /** @return the Numeral as printed, for example {@code "20"} or {@code "00"} */
        public String numeral() {
            return numeral;
        }

        /** @return whether it carries the 6/9 underline */
        public boolean underlined() {
            return underlined;
        }

        /** @return its cap height in mm (the underline below the baseline not counted) */
        public double heightMm() {
            return heightMm;
        }

        /** @return the middle of its bounding box along the face frame's right, in mm */
        public double s() {
            return s;
        }

        /** @return the middle of its bounding box along the face frame's up, in mm */
        public double t() {
            return t;
        }

        /** @return how far it is turned counter-clockwise from the face frame's up, in radians */
        public double angle() {
            return angle;
        }

        /** @return the middle of its bounding box on the face, in the model frame, mm */
        public Vector3dc centre() {
            return centre;
        }

        /** @return the unit direction its top points, in the face plane */
        public Vector3dc up() {
            return up;
        }

        /** @return the unit direction it reads along (left to right), in the face plane */
        public Vector3dc right() {
            return right;
        }

        /**
         * The transform from glyph space (y down, cap height 1) to the face frame (s right, t up, mm).
         *
         * @return a new transform
         */
        public AffineTransform toFaceFrame() {
            Rectangle2D b = glyph.getBounds2D();
            AffineTransform tx = new AffineTransform();
            tx.translate(s, t);
            tx.rotate(angle);
            // Glyph space is y-down; the face frame is y-up.
            tx.scale(heightMm, -heightMm);
            tx.translate(-b.getCenterX(), -b.getCenterY());
            return tx;
        }

        /**
         * The filled outline as engraved, in the face frame (s right, t up, mm).
         *
         * @return a new outline
         */
        public Area outline() {
            Area out = new Area(glyph);
            out.transform(toFaceFrame());
            return out;
        }
    }

    private final DieKind kind;
    private final Solid solid;
    private final Solid kernel;
    private final double radius;
    private final List<Face> faces;
    private final int grid;
    private final double cellHalfMm;
    private final double heightMm;

    /** The parts of a face while it is being laid out: its flat polygon in 3D and its frame. */
    private static final class Draft {
        final int index;
        final Vector3dc normal;
        final List<Vector3d> flat = new ArrayList<>();
        final Vector3d centre = new Vector3d();
        Vector3d up;
        Vector3d right;
        final List<Vector2dc> polygon = new ArrayList<>();
        final List<Placement> placements = new ArrayList<>();
        Face frame;

        Draft(int index, Vector3dc normal) {
            this.index = index;
            this.normal = normal;
        }
    }

    private NumeralLayout(DieKind kind) {
        this.kind = kind;
        this.solid = kind.solid();
        this.radius = kind.edgeRadius();
        List<Vector3dc> normals = new ArrayList<>();
        for (Solid.Face f : solid.faces()) {
            normals.add(f.normal());
        }
        kernel = Solid.ofHalfSpaces(normals, solid.inradius() - radius);
        List<Draft> drafts = new ArrayList<>();
        for (Solid.Face f : kernel.faces()) {
            Draft d = new Draft(f.index(), f.normal());
            for (int v : f.ring()) {
                d.flat.add(new Vector3d(kernel.vertices().get(v)).fma(radius, f.normal()));
            }
            for (Vector3d p : d.flat) {
                d.centre.add(p);
            }
            d.centre.div(d.flat.size());
            drafts.add(d);
        }
        for (Draft d : drafts) {
            d.up = upFor(d, drafts);
            d.right = d.up.cross(d.normal, new Vector3d()).normalize();
            for (Vector3d p : d.flat) {
                Vector3d rel = new Vector3d(p).sub(d.centre);
                d.polygon.add(new Vector2d(rel.dot(d.right), rel.dot(d.up)));
            }
            d.frame = new Face(d.index, d.normal, d.centre, d.up, d.right, d.polygon, List.of());
        }
        double half = 0;
        for (Draft d : drafts) {
            for (Vector2dc p : d.polygon) {
                half = Math.max(half, p.length());
            }
        }
        cellHalfMm = half + CELL_PAD_MM;
        // One cell per face plus the blank one.
        grid = (int) Math.ceil(Math.sqrt(drafts.size() + 1));
        heightMm = placeNumerals(drafts);
        List<Face> built = new ArrayList<>();
        for (Draft d : drafts) {
            built.add(new Face(d.index, d.normal, d.centre, d.up, d.right, d.polygon, d.placements));
        }
        faces = List.copyOf(built);
    }

    /**
     * The layout of a Die Kind, built once and shared.
     *
     * @param kind the Die Kind
     * @return its layout
     */
    public static NumeralLayout of(DieKind kind) {
        return Cache.BY_KIND.get(Objects.requireNonNull(kind, "kind"));
    }

    /** @return the Die Kind laid out */
    public DieKind kind() {
        return kind;
    }

    /** @return the cap height every Numeral of the Die Kind shares, in mm (spec Appendix A) */
    public double numeralHeightMm() {
        return heightMm;
    }

    /** @return the faces, in face-table order */
    public List<Face> faces() {
        return faces;
    }

    /** @return the number of atlas cells along each side; the atlas has {@code grid²} cells */
    public int grid() {
        return grid;
    }

    /** @return half the side of an atlas cell, in mm on the face */
    public double cellHalfMm() {
        return cellHalfMm;
    }

    /**
     * The atlas cell a face's Numerals are in.
     *
     * @param face the face-table index
     * @return the cell, counted row by row from the top left
     */
    public int cell(int face) {
        return Objects.checkIndex(face, faces.size());
    }

    /** @return the cell of blank Die surface, the last one, which the fillets and caps map to */
    public int blankCell() {
        return grid * grid - 1;
    }

    /** @return how many mm on a flat face one unit of atlas UV spans */
    public double mmPerUv() {
        return 2 * cellHalfMm * grid;
    }

    /**
     * The atlas UV of a face-frame point.
     *
     * @param cell the atlas cell
     * @param s mm along the frame's right
     * @param t mm along the frame's up
     * @param dest receives {@code (u, v)}, {@code v} growing image-down
     * @return {@code dest}
     */
    public Vector2d uv(int cell, double s, double t, Vector2d dest) {
        double cu = 0.5 + s / (2 * cellHalfMm);
        double cv = 0.5 - t / (2 * cellHalfMm);
        return dest.set((cell % grid + cu) / grid, (cell / grid + cv) / grid);
    }

    /**
     * The atlas UV of a point on a flat face: projected into the face's frame and mapped into its cell.
     *
     * @param face the face-table index
     * @param point a point on the face, in the model frame, mm
     * @param dest receives {@code (u, v)}
     * @return {@code dest}
     */
    public Vector2d uv(int face, Vector3dc point, Vector2d dest) {
        Face f = faces.get(face);
        Vector3d rel = point.sub(f.centre(), new Vector3d());
        return uv(cell(face), rel.dot(f.right()), rel.dot(f.up()), dest);
    }

    /**
     * The flat faces as a shader table, 12 floats (three vec4) per face in the model frame: {@code (n, d)} is the
     * face plane {@code n·x = d}, and {@code (U, u0)}, {@code (V, v0)} give the atlas UV of any point on it as
     * {@code (U·x + u0, V·x + v0)}, the same affine map as {@link #uv(int, Vector3dc, Vector2d)}. Translucent
     * Dice (#43) use it to find which Numeral a refracted ray leaves through.
     *
     * @return a new array of {@code 12 × faces} floats
     */
    public float[] faceTable() {
        float[] out = new float[faces.size() * 12];
        double k = 1 / mmPerUv();
        for (Face f : faces) {
            Vector3d u = new Vector3d(f.right()).mul(k);
            Vector3d v = new Vector3d(f.up()).mul(-k);
            int c = cell(f.index());
            double u0 = (c % grid + 0.5) / grid - u.dot(f.centre());
            double v0 = (c / grid + 0.5) / grid - v.dot(f.centre());
            double[] row = {
                f.normal().x(), f.normal().y(), f.normal().z(), f.normal().dot(f.centre()),
                u.x, u.y, u.z, u0, v.x, v.y, v.z, v0,
            };
            for (int i = 0; i < 12; i++) {
                out[f.index() * 12 + i] = (float) row[i];
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Orientation (ADR-0008, geometry.md "Numeral orientation")

    private static Vector3d inPlane(Vector3dc d, Vector3dc n) {
        return new Vector3d(d).fma(-d.dot(n), n).normalize();
    }

    /** The midpoint of the flat edge between two neighbouring faces, on {@code d}'s flat face. */
    private Vector3d sharedEdgeMid(Draft d, Draft other) {
        List<Integer> ring = kernel.faces().get(d.index).ring();
        int k = kernel.neighbours(d.index).indexOf(other.index);
        if (k < 0) {
            throw new IllegalStateException("faces " + d.index + " and " + other.index + " are not neighbours");
        }
        Vector3dc a = kernel.vertices().get(ring.get(k));
        Vector3dc b = kernel.vertices().get(ring.get((k + 1) % ring.size()));
        return new Vector3d(a).add(b).mul(0.5).fma(radius, d.normal);
    }

    /** Up so that the baseline lies along the edge shared with {@code base}: the top toward the far side. */
    private Vector3d baselineToward(Draft d, Draft base) {
        return inPlane(new Vector3d(d.centre).sub(sharedEdgeMid(d, base)), d.normal);
    }

    /** Up pointing at the middle of the edge shared with {@code toward}. */
    private Vector3d topTowardEdge(Draft d, Draft toward) {
        return inPlane(sharedEdgeMid(d, toward).sub(d.centre), d.normal);
    }

    /** Up toward the face corner furthest along {@code axis}. */
    private static Vector3d topToward(Draft d, Vector3dc axis) {
        Vector3d best = null;
        for (Vector3d p : d.flat) {
            if (best == null || p.dot(axis) > best.dot(axis)) {
                best = p;
            }
        }
        return inPlane(new Vector3d(best).sub(d.centre), d.normal);
    }

    private int value(int face) {
        return Integer.parseInt(kind.numeral(face));
    }

    /** A face's neighbours, lowest Numeral first. */
    private List<Draft> neighbours(Draft d, List<Draft> all) {
        List<Draft> out = new ArrayList<>();
        for (int g : kernel.neighbours(d.index)) {
            out.add(all.get(g));
        }
        out.sort(Comparator.comparingInt(g -> value(g.index)));
        return out;
    }

    private Draft byNumeral(List<Draft> all, String numeral) {
        for (Draft d : all) {
            if (kind.numeral(d.index).equals(numeral)) {
                return d;
            }
        }
        throw new IllegalArgumentException(kind + " has no face " + numeral);
    }

    private Vector3d upFor(Draft d, List<Draft> all) {
        return switch (kind) {
            // Frame only: each of the three corner Numerals gets its own angle in placeNumerals.
            case D4 -> inPlane(new Vector3d(d4Corner(d, d4CornerFaces(d).getFirst())).sub(d.centre), d.normal);
            // Baseline toward the lowest-numbered neighbour.
            case D6 -> baselineToward(d, neighbours(d, all).getFirst());
            // Upright relative to the equator, top toward its own half's apex.
            case D8, D10 -> topToward(d, new Vector3d(0, 0, Math.signum(d.normal.z())));
            // Perpendicular to the d10's direction, so the two digits read from apex to equator (PERP_OUT).
            case PERCENTILE_D10 -> topToward(d, new Vector3d(0, 0, Math.signum(d.normal.z())))
                    .cross(d.normal).normalize();
            case D12, D20 -> {
                // d20: the photo has the 20's baseline toward the 8; d12: the 9 directly below the 12.
                Draft top = byNumeral(all, kind == DieKind.D20 ? "20" : "12");
                if (d == top) {
                    yield baselineToward(d, byNumeral(all, kind == DieKind.D20 ? "8" : "9"));
                }
                List<Draft> nb = neighbours(d, all);
                if (kind == DieKind.D20 && nb.contains(top)) {
                    // The 20's own neighbours (2, 8, 14) keep the photo's rule: baseline toward the 20.
                    yield baselineToward(d, top);
                }
                Draft nearest = nb.getFirst();
                for (Draft g : nb) {
                    // Strictly nearer by a margin, so ties (the face opposite the top) keep the lowest Numeral.
                    if (g.normal.dot(top.normal) > nearest.normal.dot(top.normal) + 1e-4) {
                        nearest = g;
                    }
                }
                yield topTowardEdge(d, nearest);
            }
        };
    }

    /** The d4 corners a face carries, as the faces named by them (face k is named by corner k), lowest first. */
    private static List<Integer> d4CornerFaces(Draft d) {
        List<Integer> out = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            if (k != d.index) {
                out.add(k);
            }
        }
        return out;
    }

    /** The flat-face corner nearest d4 corner {@code k}, which lies straight along face k's normal negated. */
    private Vector3d d4Corner(Draft d, int k) {
        Vector3d dir = new Vector3d(solid.faces().get(k).normal()).negate();
        Vector3d best = null;
        for (Vector3d p : d.flat) {
            if (best == null || p.dot(dir) > best.dot(dir)) {
                best = p;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Fitting (ADR-0008 "Size")

    /** Whether a Numeral carries the 6/9 underline: a lone 6 or 9, on every Die Kind but the d4 and d%. */
    private boolean underlines(String numeral) {
        return kind != DieKind.D4 && kind != DieKind.PERCENTILE_D10
                && (numeral.equals("6") || numeral.equals("9"));
    }

    /**
     * Inscribed-circle diameter of a flat face. Every face here (regular polygons and the d10's kites) is
     * tangential, so its inradius is exactly area / semi-perimeter.
     */
    private static double inscribedDiameter(List<Vector2dc> poly) {
        double area2 = 0;
        double perimeter = 0;
        for (int i = 0; i < poly.size(); i++) {
            Vector2dc p = poly.get(i);
            Vector2dc q = poly.get((i + 1) % poly.size());
            area2 += p.x() * q.y() - q.x() * p.y();
            perimeter += p.distance(q);
        }
        return 2 * Math.abs(area2) / perimeter;
    }

    /** Half-planes {@code {a.x, a.y, b}} with {@code a·q <= b} for a CCW polygon, inset by {@code margin}. */
    private static List<double[]> halfPlanes(List<Vector2dc> poly, double margin) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < poly.size(); i++) {
            Vector2dc p = poly.get(i);
            Vector2dc q = poly.get((i + 1) % poly.size());
            Vector2d a = new Vector2d(q.y() - p.y(), -(q.x() - p.x())).normalize();
            out.add(new double[] {a.x, a.y, a.x * p.x() + a.y * p.y() - margin});
        }
        return out;
    }

    /** Re-expresses face-frame half-planes in a Numeral frame turned by {@code angle}. */
    private static List<double[]> rotate(List<double[]> hp, double angle) {
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        List<double[]> out = new ArrayList<>();
        // Numeral right = (c, s), Numeral up = (-s, c) in the face frame.
        for (double[] h : hp) {
            out.add(new double[] {h[0] * c + h[1] * s, -h[0] * s + h[1] * c, h[2]});
        }
        return out;
    }

    /**
     * The range of centre offsets along the Numeral's up (from the face centroid) at which a {@code w × h} glyph
     * box scaled by {@code sigma} mm per cap height fits every half-plane, or {@code null} if there is none.
     */
    private static double[] deltaRange(List<double[]> hp, double w, double h, double sigma) {
        double lo = Double.NEGATIVE_INFINITY;
        double hi = Double.POSITIVE_INFINITY;
        for (double[] p : hp) {
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    double k = p[2] - sigma * (p[0] * sx * w / 2 + p[1] * sy * h / 2);
                    if (Math.abs(p[1]) < 1e-9) {
                        if (k < 0) {
                            return null;
                        }
                    } else if (p[1] > 0) {
                        hi = Math.min(hi, k / p[1]);
                    } else {
                        lo = Math.max(lo, k / p[1]);
                    }
                }
            }
        }
        return lo <= hi ? new double[] {lo, hi} : null;
    }

    /** The largest scale at which a {@code w × h} glyph box fits the half-planes somewhere along its up. */
    private static double maxSigma(List<double[]> hp, double w, double h) {
        double lo = 0;
        double hi = 100;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (deltaRange(hp, w, h, mid) != null) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** One Numeral to fit: its face, text, angle in the face frame and the region (in its own frame) it must stay in. */
    private record Slot(Draft draft, String numeral, boolean underlined, double angle, List<double[]> region) {}

    /**
     * Chessex makes Numerals as large as possible on each face, but on a real die they all share one height.
     * So: find the largest height every Numeral of the kind fits at, take {@link #SIZE_FRACTION} of it, cap it at
     * {@link #CAP_OF_INSCRIBED} of the smallest inscribed diameter, then centre each Numeral within the range of
     * positions along its up that still fit.
     *
     * @return the shared height, in mm
     */
    private double placeNumerals(List<Draft> drafts) {
        List<Slot> slots = new ArrayList<>();
        for (Draft d : drafts) {
            List<double[]> face = halfPlanes(d.polygon, MARGIN_MM);
            if (kind == DieKind.D4) {
                for (int k : d4CornerFaces(d)) {
                    Vector3d rel = d4Corner(d, k).sub(d.centre);
                    Vector2d corner = new Vector2d(rel.dot(d.right), rel.dot(d.up));
                    double angle = Math.atan2(-corner.x, corner.y);
                    // Keep each corner Numeral in its corner's share of the face: behind a line parallel to the
                    // opposite edge, D4_CORNER_FRACTION of the way down the median. The centroid is the origin, so
                    // the opposite edge's midpoint is -corner/2 and that line crosses the median at
                    // corner·(1 - 1.5 f).
                    List<double[]> region = new ArrayList<>(face);
                    Vector2d a = new Vector2d(corner).negate().normalize();
                    Vector2d onLine = new Vector2d(corner).mul(1 - 1.5 * D4_CORNER_FRACTION);
                    region.add(new double[] {a.x, a.y, a.dot(onLine) - 0.1});
                    slots.add(new Slot(d, kind.numeral(k), false, angle, rotate(region, angle)));
                }
            } else {
                String numeral = kind.numeral(d.index);
                slots.add(new Slot(d, numeral, underlines(numeral), 0, face));
            }
        }
        double sigma = Double.POSITIVE_INFINITY;
        for (Slot s : slots) {
            Rectangle2D b = FONT.text(s.numeral(), false).getBounds2D();
            sigma = Math.min(sigma, maxSigma(s.region(), b.getWidth(), b.getHeight()));
        }
        sigma *= SIZE_FRACTION;
        // Issue #15's verdict: "as large as possible" is capped. On a square face the width never binds, so the
        // d6 Numeral grew to 12.5 mm; 11.6 mm was picked by eye, which is 0.80 of the d6's inscribed diameter.
        double inscribed = Double.POSITIVE_INFINITY;
        for (Draft d : drafts) {
            inscribed = Math.min(inscribed, inscribedDiameter(d.polygon));
        }
        sigma = Math.min(sigma, CAP_OF_INSCRIBED * inscribed);
        for (Slot s : slots) {
            Rectangle2D b = FONT.text(s.numeral(), s.underlined()).getBounds2D();
            double[] range = deltaRange(s.region(), b.getWidth(), b.getHeight(), sigma);
            if (range == null) {
                // Every Numeral of a Die Kind shares one height (ADR-0008), so an underline that doesn't fit is a
                // layout error, not something to shrink round.
                throw new IllegalStateException(kind + " " + s.numeral() + " does not fit its face at "
                        + sigma + " mm");
            }
            double delta = (range[0] + range[1]) / 2;
            double upX = -Math.sin(s.angle());
            double upY = Math.cos(s.angle());
            s.draft().placements.add(new Placement(s.numeral(), s.underlined(), sigma, delta * upX, delta * upY,
                    s.angle(), s.draft().frame));
        }
        return sigma;
    }

    /** Lays out every Die Kind once, on first use. */
    private static final class Cache {
        private static final Map<DieKind, NumeralLayout> BY_KIND = build();

        private static Map<DieKind, NumeralLayout> build() {
            Map<DieKind, NumeralLayout> out = new EnumMap<>(DieKind.class);
            for (DieKind kind : DieKind.values()) {
                out.put(kind, new NumeralLayout(kind));
            }
            return out;
        }
    }
}
