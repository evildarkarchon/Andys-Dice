package andysdice.prototype.materials;

import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * PROTOTYPE. One Die Kind made concrete: the rounded solid, where and how each numeral sits on each
 * face, and the meshes that draw it.
 *
 * <p>The rounded solid is the Minkowski sum of a shrunken "kernel" polyhedron and a sphere of the
 * edge radius: flat faces offset by r, cylindrical fillets along edges, spherical caps at vertices.
 * That is also exactly what Jolt's convex radius produces (ADR-0003), so physics and rendering can
 * share one definition.
 *
 * <p>Each face has a 2D frame (origin at the flat face's centroid, {@code up} = the numeral's top
 * direction, {@code right} = up x n) and one atlas cell. Numerals are rasterised into the cell already
 * rotated, so UVs are a plain projection onto the frame.
 */
final class DieModel {
    /** Clearance between a numeral and the start of the edge fillet, in mm. */
    static final float MARGIN_MM = 0.45f;
    /** On the d4, each corner numeral stays within this fraction of the way from its corner to the edges. */
    static final float D4_CORNER_FRACTION = 0.5f;
    /** Numeral cap height never exceeds this fraction of the flat face's inscribed-circle diameter. */
    static final float CAP_OF_INSCRIBED = 0.80f;
    /** Target vertex spacing of the dense (displaced) mesh, in mm. */
    static final float DENSE_SPACING_MM = Float.parseFloat(System.getProperty("materials.denseSpacing", "0.09"));
    /** Subdivisions across an edge fillet; caps use the same count along each side. */
    private static final int ROUND_STEPS = 6;

    /** One numeral on a face, in the face's 2D frame (mm). Angle rotates the numeral's up CCW from the face's up. */
    record Placement(String text, boolean marked, Area glyph, float sigma, float s, float t, float angle) {
    }

    /** A numbered face of the die. */
    static final class FaceLayout {
        Solid.Face face;
        int cell;
        String label;
        Vector3f n, c, up, right;
        /** The flat face (kernel ring offset by r), 3D, CCW from outside. */
        List<Vector3f> flat = new ArrayList<>();
        /** The same polygon in the face's 2D frame (s right, t up). */
        List<Vector2f> poly = new ArrayList<>();
        List<Placement> placements = new ArrayList<>();
        /** Orientation note for the HUD, e.g. "baseline toward 20". */
        String orientNote = "";
    }

    final DieKind kind;
    final Params.Structure st;
    final float r;
    final Solid solid;
    final StrokeFont font;
    final List<FaceLayout> layouts = new ArrayList<>();
    /** Atlas grid size (cells per side); the last cell is blank die surface. */
    final int grid;
    /** Half the side of an atlas cell in mm. */
    final float cellHalfMm;
    final MeshData mesh;
    /** Numeral height in mm shared by every numeral of this kind (cap height, without the 6/9 mark). */
    float capHeightMm;

    DieModel(DieKind kind, Params.Structure st) {
        this.kind = kind;
        this.st = st;
        this.r = st.edgeRadius();
        this.font = new StrokeFont(st.style(), st.weight());
        List<DieKind.FaceSpec> specs = kind.faces();
        List<Vector3f> normals = new ArrayList<>();
        for (DieKind.FaceSpec f : specs) normals.add(f.normal());
        solid = new Solid(normals, kind.inradius - r);

        for (Solid.Face f : solid.faces) {
            FaceLayout l = new FaceLayout();
            l.face = f;
            l.cell = f.index();
            l.label = specs.get(f.index()).label();
            l.n = f.normal();
            for (int v : f.ring()) l.flat.add(new Vector3f(solid.vertices.get(v)).add(new Vector3f(l.n).mul(r)));
            l.c = new Vector3f();
            for (Vector3f p : l.flat) l.c.add(p);
            l.c.div(l.flat.size());
            layouts.add(l);
        }
        for (FaceLayout l : layouts) {
            l.up = upFor(l);
            l.right = new Vector3f(l.up).cross(l.n).normalize();
            for (Vector3f p : l.flat) {
                Vector3f d = new Vector3f(p).sub(l.c);
                l.poly.add(new Vector2f(d.dot(l.right), d.dot(l.up)));
            }
        }
        float half = 0;
        for (FaceLayout l : layouts) for (Vector2f p : l.poly) half = Math.max(half, p.length());
        cellHalfMm = half + 0.15f;
        grid = (int) Math.ceil(Math.sqrt(layouts.size() + 1));
        placeNumerals();
        mesh = buildMesh(false);
    }

    // ---------------------------------------------------------------------------------------------
    // Orientation

    private FaceLayout byLabel(String label) {
        for (FaceLayout l : layouts) if (l.label.equals(label)) return l;
        throw new IllegalArgumentException(kind + " has no face " + label);
    }

    private static Vector3f inPlane(Vector3f d, Vector3f n) {
        return new Vector3f(d).sub(new Vector3f(n).mul(d.dot(n))).normalize();
    }

    /** Numeral up so its baseline lies along the edge shared with {@code base}: top toward the far corner. */
    private Vector3f baselineToward(FaceLayout l, FaceLayout base) {
        int[] e = solid.sharedEdge(l.face, base.face);
        Vector3f mid = new Vector3f(solid.vertices.get(e[0])).add(solid.vertices.get(e[1])).mul(0.5f).add(new Vector3f(l.n).mul(r));
        l.orientNote = "baseline toward " + base.label;
        return inPlane(new Vector3f(l.c).sub(mid), l.n);
    }

    /** Numeral top pointing at the middle of the edge shared with {@code toward} (Alea's "E" orientation). */
    private Vector3f topTowardEdge(FaceLayout l, FaceLayout toward) {
        int[] e = solid.sharedEdge(l.face, toward.face);
        Vector3f mid = new Vector3f(solid.vertices.get(e[0])).add(solid.vertices.get(e[1])).mul(0.5f).add(new Vector3f(l.n).mul(r));
        l.orientNote = "top toward " + toward.label + " (edge)";
        return inPlane(new Vector3f(mid).sub(l.c), l.n);
    }

    /** Numeral top toward the face corner furthest along {@code axis}. */
    private Vector3f topToward(FaceLayout l, Vector3f axis) {
        Vector3f best = null;
        for (Vector3f p : l.flat) if (best == null || p.dot(axis) > best.dot(axis)) best = p;
        return inPlane(new Vector3f(best).sub(l.c), l.n);
    }

    private List<FaceLayout> neighbours(FaceLayout l) {
        List<FaceLayout> out = new ArrayList<>();
        for (FaceLayout g : layouts) if (g != l && solid.sharedEdge(l.face, g.face) != null) out.add(g);
        out.sort(Comparator.comparingInt(g -> Integer.parseInt(g.label)));
        return out;
    }

    private Vector3f upFor(FaceLayout l) {
        switch (kind) {
            case D4 -> {
                // Frame only: each of the three corner numerals gets its own angle in placeNumerals().
                return inPlane(new Vector3f(cornerOf(l, lowestCornerLabel(l))).sub(l.c), l.n);
            }
            case D6 -> {
                return baselineToward(l, neighbours(l).getFirst());
            }
            case D8, D10 -> {
                // Research §3.4/§3.5: upright relative to the equator, top toward its own half's apex.
                l.orientNote = "top toward own apex";
                return topToward(l, new Vector3f(0, 0, Math.signum(l.n.z)));
            }
            case D_PERCENT -> {
                Vector3f up10 = topToward(l, new Vector3f(0, 0, Math.signum(l.n.z)));
                Vector3f across = new Vector3f(up10).cross(l.n).normalize();
                l.orientNote = st.percentDir().name().toLowerCase();
                return switch (st.percentDir()) {
                    case PERP_OUT -> across;
                    case PERP_IN -> across.negate();
                    case PARALLEL -> up10;
                };
            }
            case D12, D20 -> {
                String top = kind == DieKind.D20 ? "20" : "12";
                // d20: Chessex photo has the 20's baseline toward the 8 (research §3.7);
                // d12: the photo has the 9 directly below the 12 (§3.6).
                String topBase = kind == DieKind.D20 ? "8" : "9";
                FaceLayout topFace = byLabel(top);
                if (l.label.equals(top)) return baselineToward(l, byLabel(topBase));
                List<FaceLayout> nb = neighbours(l);
                FaceLayout nearest = nb.getFirst();
                for (FaceLayout g : nb) if (g.n.dot(topFace.n) > nearest.n.dot(topFace.n) + 1e-4f) nearest = g;
                if (st.orient() == Params.OrientRule.TOWARD_TOP && !(kind == DieKind.D20 && nb.contains(topFace))) {
                    return topTowardEdge(l, nearest);
                }
                if (nb.contains(topFace)) return baselineToward(l, topFace);
                FaceLayout pick = switch (st.orient()) {
                    case LOWEST -> nb.getFirst();
                    case HIGHEST -> nb.getLast();
                    case AWAY_FROM_TOP, TOWARD_TOP -> nearest;
                };
                return baselineToward(l, pick);
            }
        }
        throw new IllegalStateException();
    }

    /** d4 face labels its three corners; the face itself is named by the corner it lacks. */
    private List<Integer> d4CornerLabels(FaceLayout l) {
        List<Integer> out = new ArrayList<>();
        for (int k = 1; k <= 4; k++) if (!l.label.equals(Integer.toString(k))) out.add(k);
        return out;
    }

    private int lowestCornerLabel(FaceLayout l) {
        return d4CornerLabels(l).getFirst();
    }

    /** The flat-face corner nearest the d4 corner labelled {@code label}. */
    private Vector3f cornerOf(FaceLayout l, int label) {
        Vector3f dir = new Vector3f(DieKind.D4_CORNERS[label - 1]).normalize();
        Vector3f best = null;
        for (Vector3f p : l.flat) if (best == null || p.dot(dir) > best.dot(dir)) best = p;
        return best;
    }

    // ---------------------------------------------------------------------------------------------
    // Numeral fitting

    /**
     * Inscribed-circle diameter of a flat face. Every face here (regular polygons and the d10's kites)
     * is tangential, so the inradius is exactly area / semi-perimeter.
     */
    private static float inscribedDiameter(List<Vector2f> poly) {
        float area2 = 0, perimeter = 0;
        for (int i = 0; i < poly.size(); i++) {
            Vector2f p = poly.get(i), q = poly.get((i + 1) % poly.size());
            area2 += p.x * q.y - q.x * p.y;
            perimeter += p.distance(q);
        }
        // r = A / s = (area2 / 2) / (perimeter / 2), so the diameter is 2 * area2 / perimeter.
        return 2 * Math.abs(area2) / perimeter;
    }

    /** Half-planes {a.x, a.y, b} with a·q <= b for a CCW polygon, inset by {@code margin}. */
    private static List<float[]> halfPlanes(List<Vector2f> poly, float margin) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < poly.size(); i++) {
            Vector2f p = poly.get(i), q = poly.get((i + 1) % poly.size());
            Vector2f a = new Vector2f(q.y - p.y, -(q.x - p.x)).normalize();
            out.add(new float[]{a.x, a.y, a.dot(p) - margin});
        }
        return out;
    }

    /** Re-expresses face-frame half-planes in a numeral frame rotated by {@code angle}. */
    private static List<float[]> rotate(List<float[]> hp, float angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        List<float[]> out = new ArrayList<>();
        // Numeral right = (c, s), numeral up = (-s, c) in the face frame.
        for (float[] h : hp) out.add(new float[]{h[0] * c + h[1] * s, -h[0] * s + h[1] * c, h[2]});
        return out;
    }

    /**
     * Range of centre offsets (along the numeral's up, from the face centroid) at which a
     * {@code w x h} em box scaled by {@code sigma} mm/em fits every half-plane; {@code null} if none.
     */
    private static float[] deltaRange(List<float[]> hp, float w, float h, float sigma) {
        float lo = Float.NEGATIVE_INFINITY, hi = Float.POSITIVE_INFINITY;
        for (float[] p : hp) {
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    float k = p[2] - sigma * (p[0] * sx * w / 2 + p[1] * sy * h / 2);
                    if (Math.abs(p[1]) < 1e-6f) {
                        if (k < 0) return null;
                    } else if (p[1] > 0) {
                        hi = Math.min(hi, k / p[1]);
                    } else {
                        lo = Math.max(lo, k / p[1]);
                    }
                }
            }
        }
        return lo <= hi ? new float[]{lo, hi} : null;
    }

    private static float maxSigma(List<float[]> hp, float w, float h) {
        float lo = 0, hi = 100;
        for (int i = 0; i < 40; i++) {
            float mid = (lo + hi) / 2;
            if (deltaRange(hp, w, h, mid) != null) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** One numeral to fit: its text, its numeral-frame angle and the region it must stay inside. */
    private record Slot(FaceLayout layout, String text, boolean marked, float angle, List<float[]> region) {
    }

    /**
     * Chessex makes numerals "as large as possible on each face" (research §4), but on a real die they
     * all share one height. So: find the largest scale every numeral of the kind fits at, take the
     * size fraction of it, then centre each numeral within the range of positions that still fit.
     */
    private void placeNumerals() {
        List<Slot> slots = new ArrayList<>();
        for (FaceLayout l : layouts) {
            List<float[]> face = halfPlanes(l.poly, MARGIN_MM);
            if (kind == DieKind.D4) {
                for (int label : d4CornerLabels(l)) {
                    Vector3f corner3 = cornerOf(l, label);
                    Vector3f d = new Vector3f(corner3).sub(l.c);
                    Vector2f corner = new Vector2f(d.dot(l.right), d.dot(l.up));
                    float angle = (float) Math.atan2(-corner.x, corner.y);
                    // Keep each corner numeral in the corner's share of the face: behind a line parallel to
                    // the opposite edge, D4_CORNER_FRACTION of the way down the median. The centroid is the
                    // origin, so the opposite edge's midpoint is -corner/2 and that line crosses the median
                    // at corner·(1 - 1.5f).
                    List<float[]> region = new ArrayList<>(face);
                    Vector2f a = new Vector2f(corner).negate().normalize();
                    Vector2f onLine = new Vector2f(corner).mul(1 - 1.5f * D4_CORNER_FRACTION);
                    region.add(new float[]{a.x, a.y, a.dot(onLine) - 0.1f});
                    slots.add(new Slot(l, Integer.toString(label), false, angle, rotate(region, angle)));
                }
            } else {
                slots.add(new Slot(l, l.label, kind.marks(l.label), 0, face));
            }
        }
        float sigma = Float.POSITIVE_INFINITY;
        for (Slot s : slots) {
            Rectangle2D b = font.text(s.text(), false, st.mark()).getBounds2D();
            sigma = Math.min(sigma, maxSigma(s.region(), (float) b.getWidth(), (float) b.getHeight()));
        }
        sigma *= st.size();
        // Verdict on issue #15: "as large as possible" is capped. On a square face the width never
        // binds, so the d6 numeral grew to 12.5 mm; the human picked 11.6 mm, which is 0.80 of the d6's
        // flat-face inscribed diameter. The same fraction caps every kind.
        float inscribed = Float.POSITIVE_INFINITY;
        for (FaceLayout l : layouts) inscribed = Math.min(inscribed, inscribedDiameter(l.poly));
        sigma = Math.min(sigma, CAP_OF_INSCRIBED * inscribed);
        capHeightMm = sigma;
        for (Slot s : slots) {
            Area glyph = font.text(s.text(), s.marked(), st.mark());
            Rectangle2D b = glyph.getBounds2D();
            float sg = sigma;
            float[] range = deltaRange(s.region(), (float) b.getWidth(), (float) b.getHeight(), sg);
            // A marked 6/9 is taller; shrink just that numeral if the mark would run off the face.
            while (range == null && sg > sigma * 0.5f) {
                sg *= 0.97f;
                range = deltaRange(s.region(), (float) b.getWidth(), (float) b.getHeight(), sg);
            }
            float delta = range == null ? 0 : (range[0] + range[1]) / 2;
            float angle = s.angle();
            float upX = (float) -Math.sin(angle), upY = (float) Math.cos(angle);
            s.layout().placements.add(new Placement(s.text(), s.marked(), glyph, sg, delta * upX, delta * upY, angle));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // UVs and meshes

    float mmPerUv() {
        return 2 * cellHalfMm * grid;
    }

    int blankCell() {
        return grid * grid - 1;
    }

    /** Atlas UV for a face-frame point (s right, t up, mm) in {@code cell}. */
    Vector2f uv(int cell, float s, float t) {
        float cu = 0.5f + s / (2 * cellHalfMm), cv = 0.5f - t / (2 * cellHalfMm);
        return new Vector2f((cell % grid + cu) / grid, (cell / grid + cv) / grid);
    }

    private Vector2f uvOf(FaceLayout l, Vector3f p) {
        Vector3f d = new Vector3f(p).sub(l.c);
        return uv(l.cell, d.dot(l.right), d.dot(l.up));
    }

    /**
     * The flat faces as a shader table, 12 floats (three vec4) per face, in model space:
     * {@code (n, d)} is the face plane {@code n·x = d}; {@code (U, u0)} and {@code (V, v0)} give the
     * face's atlas UV of any point on it as {@code (U·x + u0, V·x + v0)}, the same affine map as
     * {@link #uv}. The translucent shading uses it to find where a refracted ray leaves the die and which
     * numeral (if any) it passes through on the far face. The fillets are ignored: the planes bound the
     * rounded solid closely enough for a thickness estimate.
     */
    float[] faceTable() {
        float[] out = new float[layouts.size() * 12];
        float k = 1f / (2 * cellHalfMm * grid);
        for (int i = 0; i < layouts.size(); i++) {
            FaceLayout l = layouts.get(i);
            Vector3f n = new Vector3f(l.n).normalize();
            Vector3f u = new Vector3f(l.right).mul(k), v = new Vector3f(l.up).mul(-k);
            float u0 = (l.cell % grid + 0.5f) / grid - u.dot(l.c);
            float v0 = (l.cell / grid + 0.5f) / grid - v.dot(l.c);
            float[] row = {n.x, n.y, n.z, n.dot(l.c), u.x, u.y, u.z, u0, v.x, v.y, v.z, v0};
            System.arraycopy(row, 0, out, i * 12, 12);
        }
        return out;
    }

    /**
     * Builds the die mesh. {@code dense} subdivides the flat faces to {@link #DENSE_SPACING_MM} so the
     * vertex shader can push the engraving into real geometry (variant C); fillets and caps stay coarse.
     */
    MeshData buildMesh(boolean dense) {
        MeshData.Builder b = new MeshData.Builder();
        Vector2f blankUv = uv(blankCell(), 0, 0);

        // 1. Flat faces. The bitangent is dP/dv; v grows image-down, i.e. against the numeral's up.
        for (FaceLayout l : layouts) {
            Vector3f bit = new Vector3f(l.up).negate();
            int k = l.flat.size();
            if (!dense) {
                int[] idx = new int[k];
                for (int i = 0; i < k; i++) idx[i] = b.vertex(l.flat.get(i), l.n, uvOf(l, l.flat.get(i)), l.right, bit);
                for (int i = 1; i + 1 < k; i++) b.triangle(idx[0], idx[i], idx[i + 1], l.n);
            } else if (k == 3) {
                subdivide(b, l, l.flat.get(0), l.flat.get(1), l.flat.get(2), steps(l), bit);
            } else {
                int n = steps(l);
                for (int i = 0; i < k; i++) subdivide(b, l, l.c, l.flat.get(i), l.flat.get((i + 1) % k), n, bit);
            }
        }

        // 2. Edge fillets: for each pair of faces sharing an edge, sweep the normal from one to the other.
        for (int i = 0; i < layouts.size(); i++) {
            for (int j = i + 1; j < layouts.size(); j++) {
                FaceLayout fi = layouts.get(i), fj = layouts.get(j);
                int[] shared = solid.sharedEdge(fi.face, fj.face);
                if (shared == null) continue;
                Vector3f a = solid.vertices.get(shared[0]), c = solid.vertices.get(shared[1]);
                Vector3f along = new Vector3f(c).sub(a).normalize();
                int[] rowA = new int[ROUND_STEPS + 1], rowC = new int[ROUND_STEPS + 1];
                for (int s = 0; s <= ROUND_STEPS; s++) {
                    Vector3f d = nlerp(fi.n, fj.n, s / (float) ROUND_STEPS);
                    Vector3f bt = new Vector3f(d).cross(along);
                    rowA[s] = b.vertex(new Vector3f(d).mul(r).add(a), d, blankUv, along, bt);
                    rowC[s] = b.vertex(new Vector3f(d).mul(r).add(c), d, blankUv, along, bt);
                }
                for (int s = 0; s < ROUND_STEPS; s++) {
                    Vector3f out = nlerp(fi.n, fj.n, (s + 0.5f) / ROUND_STEPS);
                    b.triangle(rowA[s], rowC[s], rowC[s + 1], out);
                    b.triangle(rowA[s], rowC[s + 1], rowA[s + 1], out);
                }
            }
        }

        // 3. Corner caps: the spherical polygon of the face normals around each kernel vertex, fanned
        //    from their mean direction and subdivided so its rim matches the fillets.
        for (int v = 0; v < solid.vertices.size(); v++) {
            List<Vector3f> ring = new ArrayList<>();
            for (FaceLayout l : layouts) for (int w : l.face.ring()) if (w == v) ring.add(l.n);
            Vector3f centre = new Vector3f();
            for (Vector3f n : ring) centre.add(n);
            centre.normalize();
            Vector3f ref = inPlane(ring.getFirst(), centre);
            Vector3f ref2 = new Vector3f(centre).cross(ref);
            ring.sort(Comparator.comparingDouble(n -> Math.atan2(n.dot(ref2), n.dot(ref))));
            for (int s = 0; s < ring.size(); s++) {
                capTriangle(b, solid.vertices.get(v), centre, ring.get(s), ring.get((s + 1) % ring.size()), blankUv);
            }
        }
        return b.build();
    }

    private int steps(FaceLayout l) {
        float longest = 0;
        for (int i = 0; i < l.flat.size(); i++) {
            longest = Math.max(longest, l.flat.get(i).distance(l.flat.get((i + 1) % l.flat.size())));
            longest = Math.max(longest, l.flat.get(i).distance(l.c));
        }
        return Math.max(1, (int) Math.ceil(longest / DENSE_SPACING_MM));
    }

    /**
     * Uniformly subdivides triangle (a, p, q) into n² triangles. Points along a -> p and a -> q are
     * computed as a + (x - a)·(m/n), so fan triangles sharing an edge from the centroid get bitwise
     * identical vertices there and the face stays crack-free.
     */
    private void subdivide(MeshData.Builder b, FaceLayout l, Vector3f a, Vector3f p, Vector3f q, int n, Vector3f bit) {
        Vector3f dp = new Vector3f(p).sub(a), dq = new Vector3f(q).sub(a);
        Vector2f ua = uvOf(l, a), up = uvOf(l, p), uq = uvOf(l, q);
        float dux = up.x - ua.x, duy = up.y - ua.y, dvx = uq.x - ua.x, dvy = uq.y - ua.y;
        int[][] g = new int[n + 1][];
        for (int i = 0; i <= n; i++) {
            g[i] = new int[n + 1 - i];
            float fi = i / (float) n;
            for (int j = 0; j <= n - i; j++) {
                float fj = j / (float) n;
                g[i][j] = b.vertex(a.x + dp.x * fi + dq.x * fj, a.y + dp.y * fi + dq.y * fj, a.z + dp.z * fi + dq.z * fj,
                        l.n, ua.x + dux * fi + dvx * fj, ua.y + duy * fi + dvy * fj, l.right, bit);
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n - i; j++) {
                b.triangle(g[i][j], g[i + 1][j], g[i][j + 1], l.n);
                if (j < n - i - 1) b.triangle(g[i + 1][j], g[i + 1][j + 1], g[i][j + 1], l.n);
            }
        }
    }

    /** Subdivided spherical triangle (centre, n1, n2) offset from kernel point {@code origin}. */
    private void capTriangle(MeshData.Builder b, Vector3f origin, Vector3f centre, Vector3f n1, Vector3f n2, Vector2f uv) {
        int steps = ROUND_STEPS;
        int[][] grid = new int[steps + 1][];
        for (int i = 0; i <= steps; i++) {
            grid[i] = new int[steps + 1 - i];
            for (int j = 0; j <= steps - i; j++) {
                float wc = (steps - i - j) / (float) steps, w1 = i / (float) steps, w2 = j / (float) steps;
                Vector3f d = new Vector3f(centre).mul(wc).add(new Vector3f(n1).mul(w1)).add(new Vector3f(n2).mul(w2)).normalize();
                Vector3f t = new Vector3f(d).cross(new Vector3f(0.31f, 1, 0.37f)).normalize();
                Vector3f bt = new Vector3f(d).cross(t);
                grid[i][j] = b.vertex(new Vector3f(d).mul(r).add(origin), d, uv, t, bt);
            }
        }
        for (int i = 0; i < steps; i++) {
            for (int j = 0; j < steps - i; j++) {
                Vector3f out = new Vector3f(b.p(grid[i][j])).sub(origin);
                b.triangle(grid[i][j], grid[i + 1][j], grid[i][j + 1], out);
                if (j < steps - i - 1) b.triangle(grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1], out);
            }
        }
    }

    private static Vector3f nlerp(Vector3f a, Vector3f b, float t) {
        return new Vector3f(a).lerp(b, t).normalize();
    }

    // ---------------------------------------------------------------------------------------------
    // Resting

    /** Labels in the order {@code N} cycles through them. */
    List<String> labels() {
        if (kind == DieKind.D4) return List.of("1", "2", "3", "4");
        List<String> out = new ArrayList<>();
        for (DieKind.FaceSpec f : kind.faces()) out.add(f.label());
        return out;
    }

    /**
     * Rotation (research frame to Y-up world) that rests the die with {@code value} showing, its numeral
     * reading upright from a camera on the -Z side at yaw 0. For the d4, {@code value} is the corner
     * pointing up and one of the faces around it turns toward the camera.
     *
     * @param yaw extra spin about +Y, in radians, applied after the numeral is aligned
     */
    Quaternionf restRotation(String value, float yaw) {
        Vector3f yUp = new Vector3f(0, 1, 0);
        if (kind == DieKind.D4) {
            int k = Integer.parseInt(value);
            Quaternionf q = new Quaternionf().rotationTo(new Vector3f(DieKind.D4_CORNERS[k - 1]).normalize(), yUp);
            FaceLayout facing = byLabel(Integer.toString(k % 4 + 1));
            Vector3f n = q.transform(new Vector3f(facing.n));
            float angle = (float) Math.atan2(n.x, n.z);
            return new Quaternionf().rotationY((float) Math.PI - angle + yaw).mul(q);
        }
        FaceLayout l = byLabel(value);
        Quaternionf q = new Quaternionf().rotationTo(l.n, yUp);
        Vector3f top = q.transform(new Vector3f(l.up));
        float angle = (float) Math.atan2(top.x, top.z);
        return new Quaternionf().rotationY(yaw - angle).mul(q);
    }
}
