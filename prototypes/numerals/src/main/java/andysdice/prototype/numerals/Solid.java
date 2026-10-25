package andysdice.prototype.numerals;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * PROTOTYPE. A convex polyhedron built as the intersection of half-spaces {@code n·x <= inradius},
 * one per face normal. This is the "frame-independent construction" the dice research recommends
 * (issue #5, §2.1): the face tables give every numbered face's outward normal, and that alone fixes
 * the solid and its numbering together.
 *
 * <p>The research tables carry four decimals, so the five planes meeting at a d10 apex don't meet at
 * exactly one point. Candidate vertices are therefore merged within a tolerance; every later stage
 * (flat faces, fillets, caps) uses the same merged vertices and the same normals, which keeps the
 * rounded mesh watertight even though each face is planar only to about 1e-4 of the die's size.
 */
final class Solid {
    /** One face: its index into the normal list, unit outward normal and vertex ring (CCW from outside). */
    record Face(int index, Vector3f normal, int[] ring) {
    }

    final List<Vector3f> vertices = new ArrayList<>();
    final List<Face> faces = new ArrayList<>();

    /**
     * @param normals  outward face normals (need not be unit length; they are normalised)
     * @param inradius distance from the centre to every face plane
     */
    Solid(List<Vector3f> normals, float inradius) {
        List<Vector3f> n = new ArrayList<>();
        for (Vector3f v : normals) n.add(new Vector3f(v).normalize());
        float tolMerge = 2e-3f * inradius, tolInside = 2e-3f * inradius;

        for (int i = 0; i < n.size(); i++) {
            for (int j = i + 1; j < n.size(); j++) {
                for (int k = j + 1; k < n.size(); k++) {
                    Vector3f a = n.get(i), b = n.get(j), c = n.get(k);
                    Vector3f bc = new Vector3f(b).cross(c), ca = new Vector3f(c).cross(a), ab = new Vector3f(a).cross(b);
                    float det = a.dot(bc);
                    if (Math.abs(det) < 1e-4f) continue;
                    // Cramer's rule for a·x = b·x = c·x = inradius.
                    Vector3f x = new Vector3f(bc).add(ca).add(ab).mul(inradius / det);
                    boolean inside = true;
                    for (Vector3f m : n) {
                        if (m.dot(x) > inradius + tolInside) {
                            inside = false;
                            break;
                        }
                    }
                    if (inside && vertices.stream().noneMatch(v -> v.distance(x) < tolMerge)) vertices.add(x);
                }
            }
        }

        for (int f = 0; f < n.size(); f++) {
            Vector3f nf = n.get(f);
            List<Integer> on = new ArrayList<>();
            for (int v = 0; v < vertices.size(); v++) {
                if (Math.abs(nf.dot(vertices.get(v)) - inradius) < 5 * tolInside) on.add(v);
            }
            if (on.size() < 3) throw new IllegalStateException("face " + f + " has only " + on.size() + " vertices");
            Vector3f c = new Vector3f();
            for (int v : on) c.add(vertices.get(v));
            c.div(on.size());
            // e1 x e2 = n, so increasing atan2 angle runs counter-clockwise seen from outside.
            Vector3f e1 = new Vector3f(vertices.get(on.get(0))).sub(c).normalize();
            Vector3f e2 = new Vector3f(nf).cross(e1);
            on.sort(Comparator.comparingDouble(v -> {
                Vector3f d = new Vector3f(vertices.get(v)).sub(c);
                return Math.atan2(d.dot(e2), d.dot(e1));
            }));
            faces.add(new Face(f, nf, on.stream().mapToInt(Integer::intValue).toArray()));
        }
    }

    /** The two vertices faces {@code a} and {@code b} share, or {@code null} if they share no edge. */
    int[] sharedEdge(Face a, Face b) {
        int[] out = new int[2];
        int count = 0;
        for (int v : a.ring()) {
            for (int w : b.ring()) {
                if (v == w) {
                    if (count == 2) return null;
                    out[count++] = v;
                }
            }
        }
        return count == 2 ? out : null;
    }

    /** Faces sharing an edge with {@code f}. */
    List<Face> neighbours(Face f) {
        List<Face> out = new ArrayList<>();
        for (Face g : faces) if (g != f && sharedEdge(f, g) != null) out.add(g);
        return out;
    }

    /** Centroid of a face's vertex ring. */
    Vector3f centroid(Face f) {
        Vector3f c = new Vector3f();
        for (int v : f.ring()) c.add(vertices.get(v));
        return c.div(f.ring().length);
    }
}
