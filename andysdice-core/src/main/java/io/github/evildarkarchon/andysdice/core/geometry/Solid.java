package io.github.evildarkarchon.andysdice.core.geometry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * A sharp convex polyhedron built as the intersection of half-spaces {@code n·x <= inradius}, one per outward
 * face normal. This is the research's frame-independent construction (real-dice-geometry §2.1): a face table's
 * normals alone fix the solid and its numbering together, so face {@code i} of the solid is face {@code i} of
 * the table it was built from.
 *
 * <p>The research tables carry four decimals, so the five planes meeting at a d10 apex don't meet in exactly one
 * point. Candidate vertices are therefore merged within a tolerance (and averaged), and a vertex belongs to a
 * face when it lies within a looser tolerance of the face's plane. Each face is planar to about 1e-4 of the
 * die's size, far below anything physics or rendering can see.
 *
 * <p>Instances are immutable. Lengths are in whatever unit the inradius is given in (mm for a {@link DieKind}).
 */
public final class Solid {

    /**
     * One face of a solid.
     *
     * @param index  the face's position in the face table the solid was built from
     * @param normal the face's unit outward normal
     * @param ring   indices into {@link Solid#vertices()} of the face's corners, counter-clockwise seen from
     *               outside the solid
     */
    public record Face(int index, Vector3dc normal, List<Integer> ring) {

        /** Copies the normal and ring so that the face cannot change under its solid. */
        public Face {
            normal = new Vector3d(normal);
            ring = List.copyOf(ring);
        }
    }

    private final double inradius;
    private final List<Vector3dc> vertices;
    private final List<Face> faces;

    private Solid(double inradius, List<Vector3dc> vertices, List<Face> faces) {
        this.inradius = inradius;
        this.vertices = vertices;
        this.faces = faces;
    }

    /**
     * Builds the solid bounded by one plane per normal, each at distance {@code inradius} from the origin.
     *
     * @param normals  outward face normals, in face order; they need not be unit length
     * @param inradius the distance from the origin to every face plane, greater than zero
     * @return the solid, whose face {@code i} lies on the plane of {@code normals.get(i)}
     * @throws IllegalArgumentException if {@code inradius} is not positive
     * @throws IllegalStateException    if a normal's plane touches the solid in fewer than three vertices, so the
     *                                  normals don't describe a closed polyhedron with that face
     */
    public static Solid ofHalfSpaces(List<? extends Vector3dc> normals, double inradius) {
        if (!(inradius > 0)) {
            throw new IllegalArgumentException("inradius must be positive: " + inradius);
        }
        List<Vector3d> n = new ArrayList<>(normals.size());
        for (Vector3dc v : normals) {
            n.add(v.normalize(new Vector3d()));
        }
        // Tolerances scale with the solid so that the kernel solids of rounded meshes (a smaller inradius)
        // merge exactly the same vertices as the full-size ones.
        double tolMerge = 2e-3 * inradius;
        double tolInside = 2e-3 * inradius;

        List<Vector3d> sums = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < n.size(); i++) {
            for (int j = i + 1; j < n.size(); j++) {
                for (int k = j + 1; k < n.size(); k++) {
                    Vector3d x = intersection(n.get(i), n.get(j), n.get(k), inradius);
                    if (x == null || !insideAll(n, x, inradius + tolInside)) {
                        continue;
                    }
                    merge(sums, counts, x, tolMerge);
                }
            }
        }
        List<Vector3dc> vertices = new ArrayList<>(sums.size());
        for (int c = 0; c < sums.size(); c++) {
            vertices.add(new Vector3d(sums.get(c)).div(counts.get(c)));
        }

        List<Face> faces = new ArrayList<>(n.size());
        for (int f = 0; f < n.size(); f++) {
            faces.add(new Face(f, n.get(f), ring(n.get(f), vertices, inradius, 5 * tolInside, f)));
        }
        return new Solid(inradius, List.copyOf(vertices), List.copyOf(faces));
    }

    /**
     * The distance from the solid's centre to each face plane.
     *
     * @return the inradius the solid was built with
     */
    public double inradius() {
        return inradius;
    }

    /**
     * The solid's corners.
     *
     * @return the vertices, in no particular order; {@link Face#ring()} indexes into this list
     */
    public List<Vector3dc> vertices() {
        return vertices;
    }

    /**
     * The solid's faces.
     *
     * @return the faces in face-table order, so {@code faces().get(i).index() == i}
     */
    public List<Face> faces() {
        return faces;
    }

    /**
     * The faces that share an edge with a face, counter-clockwise seen from outside the solid: neighbour
     * {@code k} lies across the edge from {@code ring().get(k)} to {@code ring().get(k + 1)}. The research's
     * neighbour tables list the same cycles, so a mirrored numbering would show up here as a reversed cycle.
     *
     * @param face a face index, {@code 0 <= face < faces().size()}
     * @return the indices of the neighbouring faces, one per edge of the face
     * @throws IndexOutOfBoundsException if {@code face} is out of range
     */
    public List<Integer> neighbours(int face) {
        List<Integer> ring = faces.get(face).ring();
        List<Integer> out = new ArrayList<>(ring.size());
        for (int k = 0; k < ring.size(); k++) {
            int a = ring.get(k);
            int b = ring.get((k + 1) % ring.size());
            out.add(acrossEdge(face, a, b));
        }
        return List.copyOf(out);
    }

    /** The face other than {@code face} whose ring holds both {@code a} and {@code b}. */
    private int acrossEdge(int face, int a, int b) {
        for (Face other : faces) {
            if (other.index() != face && other.ring().contains(a) && other.ring().contains(b)) {
                return other.index();
            }
        }
        // Unreachable on a closed convex solid: every edge borders exactly two faces.
        throw new IllegalStateException("edge " + a + "-" + b + " of face " + face + " borders no other face");
    }

    /** Solves {@code a·x = b·x = c·x = d} by Cramer's rule, or returns null if the three planes have no single point. */
    private static Vector3d intersection(Vector3dc a, Vector3dc b, Vector3dc c, double d) {
        Vector3d bc = b.cross(c, new Vector3d());
        double det = a.dot(bc);
        if (Math.abs(det) < 1e-4) {
            return null;
        }
        Vector3d ca = c.cross(a, new Vector3d());
        Vector3d ab = a.cross(b, new Vector3d());
        return bc.add(ca).add(ab).mul(d / det);
    }

    /** Whether {@code x} lies on the inner side of every plane {@code n·x = limit}. */
    private static boolean insideAll(List<Vector3d> normals, Vector3dc x, double limit) {
        for (Vector3dc m : normals) {
            if (m.dot(x) > limit) {
                return false;
            }
        }
        return true;
    }

    /** Adds {@code x} to the first cluster whose running mean is within {@code tol}, or starts a new cluster. */
    private static void merge(List<Vector3d> sums, List<Integer> counts, Vector3dc x, double tol) {
        for (int c = 0; c < sums.size(); c++) {
            Vector3d mean = new Vector3d(sums.get(c)).div(counts.get(c));
            if (mean.distance(x) < tol) {
                sums.get(c).add(x);
                counts.set(c, counts.get(c) + 1);
                return;
            }
        }
        sums.add(new Vector3d(x));
        counts.add(1);
    }

    /**
     * The vertices on the plane {@code normal·x = inradius}, sorted counter-clockwise seen from outside.
     *
     * @throws IllegalStateException if fewer than three vertices lie on the plane
     */
    private static List<Integer> ring(Vector3dc normal, List<Vector3dc> vertices, double inradius, double tol, int face) {
        List<Integer> on = new ArrayList<>();
        for (int v = 0; v < vertices.size(); v++) {
            if (Math.abs(normal.dot(vertices.get(v)) - inradius) < tol) {
                on.add(v);
            }
        }
        if (on.size() < 3) {
            throw new IllegalStateException("face " + face + " touches only " + on.size() + " vertices");
        }
        Vector3d centre = new Vector3d();
        for (int v : on) {
            centre.add(vertices.get(v));
        }
        centre.div(on.size());
        // e1 × e2 = normal, so an increasing atan2 angle in the (e1, e2) frame runs counter-clockwise seen
        // from outside.
        Vector3d e1 = vertices.get(on.getFirst()).sub(centre, new Vector3d()).normalize();
        Vector3d e2 = normal.cross(e1, new Vector3d());
        on.sort(Comparator.comparingDouble(v -> {
            Vector3d d = vertices.get(v).sub(centre, new Vector3d());
            return Math.atan2(d.dot(e2), d.dot(e1));
        }));
        return on;
    }
}
