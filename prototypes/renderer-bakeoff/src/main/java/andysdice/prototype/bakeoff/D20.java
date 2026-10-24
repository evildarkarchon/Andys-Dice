package andysdice.prototype.bakeoff;

import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * PROTOTYPE. Procedural, rounded Chessex-layout d20.
 *
 * <p>The rounded solid is the Minkowski sum of a shrunken icosahedron (the "kernel") and a sphere of
 * radius {@link #EDGE_RADIUS}: flat faces offset by r, cylindrical strips along the edges and
 * spherical caps at the vertices. Strips and caps subdivide with the same normalised lerp, so their
 * shared vertices coincide and the mesh is watertight without a welding pass.
 *
 * <p>Numbering and normals come from "How are real D&D dice shaped and numbered?" (issue #5, §3.7):
 * opposite faces sum to 21 and, with the 20 upright, the 8 sits directly below it.
 */
final class D20 {
    /** Face-to-face size of a real Chessex d20 is 19-20 mm; we model 20 mm. */
    static final float INRADIUS = 10f;
    /** Research suggests 0.5-1.0 mm fillets for tumbled dice. */
    static final float EDGE_RADIUS = 0.9f;
    /** Subdivisions across an edge fillet; caps use the same count along each side. */
    private static final int ROUND_STEPS = 6;
    private static final float CANONICAL_INRADIUS = 1.511523f; // icosahedron with edge 2

    /** Atlas layout: 5x5 cells, cells 0..19 hold numerals 1..20, cell 24 is blank die surface. */
    static final int ATLAS_GRID = 5;
    static final int BLANK_CELL = 24;
    /** Triangle corners inside a cell (cell-local 0..1, v down): top, bottom-left, bottom-right. */
    static final Vector2f CELL_TOP = new Vector2f(0.5f, 0.119f);
    static final Vector2f CELL_BL = new Vector2f(0.06f, 0.881f);
    static final Vector2f CELL_BR = new Vector2f(0.94f, 0.881f);

    private static final float PHI = (float) ((1 + Math.sqrt(5)) / 2);

    /** Outward unit normal per face number (index = number - 1), from the research table. */
    private static final float[][] FACE_NORMALS = {
            {0.5774f, 0.5774f, 0.5774f}, {0f, -0.9342f, -0.3568f}, {-0.5774f, 0.5774f, 0.5774f},
            {0.3568f, 0f, -0.9342f}, {0.9342f, -0.3568f, 0f}, {-0.5774f, 0.5774f, -0.5774f},
            {0.3568f, 0f, 0.9342f}, {-0.9342f, -0.3568f, 0f}, {0f, 0.9342f, -0.3568f},
            {-0.5774f, -0.5774f, 0.5774f}, {0.5774f, 0.5774f, -0.5774f}, {0f, -0.9342f, 0.3568f},
            {0.9342f, 0.3568f, 0f}, {-0.3568f, 0f, -0.9342f}, {0.5774f, -0.5774f, 0.5774f},
            {-0.9342f, 0.3568f, 0f}, {-0.3568f, 0f, 0.9342f}, {0.5774f, -0.5774f, -0.5774f},
            {0f, 0.9342f, 0.3568f}, {-0.5774f, -0.5774f, -0.5774f},
    };

    /** One numbered face of the kernel. */
    record Face(int number, Vector3f normal, int[] verts, int top, int left, int right) {
    }

    final MeshData mesh;
    final List<Face> faces = new ArrayList<>();
    private final Vector3f[] kernel = new Vector3f[12];

    D20() {
        // Canonical icosahedron: cyclic permutations of (0, +-1, +-phi), then shrink so that after
        // inflating by EDGE_RADIUS the faces sit exactly INRADIUS from the centre.
        float s = (INRADIUS - EDGE_RADIUS) / CANONICAL_INRADIUS;
        int k = 0;
        for (int a = -1; a <= 1; a += 2) {
            for (int b = -1; b <= 1; b += 2) {
                kernel[k++] = new Vector3f(0, a, b * PHI).mul(s);
                kernel[k++] = new Vector3f(a, b * PHI, 0).mul(s);
                kernel[k++] = new Vector3f(b * PHI, 0, a).mul(s);
            }
        }
        List<Vector3f> normals = new ArrayList<>();
        for (float[] n : FACE_NORMALS) normals.add(new Vector3f(n[0], n[1], n[2]).normalize());
        List<int[]> faceVerts = new ArrayList<>();
        for (Vector3f n : normals) faceVerts.add(closestThree(n));

        for (int f = 0; f < 20; f++) {
            int number = f + 1;
            int[] v = faceVerts.get(f);
            int top = chooseTop(number, faceVerts);
            int[] others = new int[2];
            int o = 0;
            for (int vi : v) if (vi != top) others[o++] = vi;
            Vector3f n = normals.get(f);
            // (top, left, right) must run counter-clockwise seen from outside so the numeral isn't mirrored.
            Vector3f cross = new Vector3f(kernel[others[0]]).sub(kernel[top]).cross(new Vector3f(kernel[others[1]]).sub(kernel[top]));
            int left = cross.dot(n) > 0 ? others[0] : others[1];
            int right = left == others[0] ? others[1] : others[0];
            faces.add(new Face(number, n, v, top, left, right));
        }
        mesh = buildMesh();
    }

    /** Index of the kernel vertex furthest along {@code n}, three times: the face's corners. */
    private int[] closestThree(Vector3f n) {
        Integer[] order = new Integer[12];
        for (int i = 0; i < 12; i++) order[i] = i;
        java.util.Arrays.sort(order, Comparator.comparingDouble(i -> -kernel[i].dot(n)));
        return new int[]{order[0], order[1], order[2]};
    }

    /**
     * Which corner of a face the numeral's top points at. The Chessex photo shows faces next to the
     * 20 with their baselines toward the 20, and the 20 with its baseline toward the 8; elsewhere the
     * orientation is unconfirmed, so the rule just picks the corner away from the lowest neighbour.
     */
    private static int chooseTop(int number, List<int[]> faceVerts) {
        int[] mine = faceVerts.get(number - 1);
        int baseNeighbour;
        if (number == 20) baseNeighbour = 8;
        else if (sharedCount(mine, faceVerts.get(19)) == 2) baseNeighbour = 20;
        else {
            baseNeighbour = -1;
            for (int g = 1; g <= 20 && baseNeighbour < 0; g++)
                if (g != number && sharedCount(mine, faceVerts.get(g - 1)) == 2) baseNeighbour = g;
        }
        int[] other = faceVerts.get(baseNeighbour - 1);
        for (int v : mine) if (!contains(other, v)) return v;
        throw new IllegalStateException("face " + number + " shares no edge with " + baseNeighbour);
    }

    private static int sharedCount(int[] a, int[] b) {
        int c = 0;
        for (int v : a) if (contains(b, v)) c++;
        return c;
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    /** UV of a cell-local point inside the atlas cell for {@code cell}. */
    static Vector2f atlasUv(int cell, Vector2f local) {
        float size = 1f / ATLAS_GRID;
        return new Vector2f((cell % ATLAS_GRID + local.x) * size, (cell / ATLAS_GRID + local.y) * size);
    }

    private MeshData buildMesh() {
        MeshData.Builder b = new MeshData.Builder();
        float r = EDGE_RADIUS;
        Vector2f blank = atlasUv(BLANK_CELL, new Vector2f(0.5f, 0.5f));

        // 1. Flat faces, each mapped to its numeral cell.
        for (Face f : faces) {
            Vector3f off = new Vector3f(f.normal).mul(r);
            Vector3f pt = new Vector3f(kernel[f.top]).add(off);
            Vector3f pl = new Vector3f(kernel[f.left]).add(off);
            Vector3f pr = new Vector3f(kernel[f.right]).add(off);
            Vector3f tangent = new Vector3f(pr).sub(pl).normalize();
            Vector3f bitangent = new Vector3f(pl).add(pr).mul(0.5f).sub(pt).normalize();
            int cell = f.number - 1;
            int it = b.vertex(pt, f.normal, atlasUv(cell, CELL_TOP), tangent, bitangent);
            int il = b.vertex(pl, f.normal, atlasUv(cell, CELL_BL), tangent, bitangent);
            int ir = b.vertex(pr, f.normal, atlasUv(cell, CELL_BR), tangent, bitangent);
            b.triangle(it, il, ir, f.normal);
        }

        // 2. Edge fillets: for each pair of faces sharing an edge, sweep the normal from one to the other.
        for (int i = 0; i < 20; i++) {
            for (int j = i + 1; j < 20; j++) {
                Face fi = faces.get(i), fj = faces.get(j);
                int[] shared = sharedVerts(fi.verts, fj.verts);
                if (shared == null) continue;
                Vector3f a = kernel[shared[0]], c = kernel[shared[1]];
                Vector3f along = new Vector3f(c).sub(a).normalize();
                int[] rowA = new int[ROUND_STEPS + 1], rowC = new int[ROUND_STEPS + 1];
                for (int s = 0; s <= ROUND_STEPS; s++) {
                    Vector3f d = nlerp(fi.normal, fj.normal, s / (float) ROUND_STEPS);
                    Vector3f bt = new Vector3f(d).cross(along);
                    rowA[s] = b.vertex(new Vector3f(d).mul(r).add(a), d, blank, along, bt);
                    rowC[s] = b.vertex(new Vector3f(d).mul(r).add(c), d, blank, along, bt);
                }
                for (int s = 0; s < ROUND_STEPS; s++) {
                    Vector3f out = nlerp(fi.normal, fj.normal, (s + 0.5f) / ROUND_STEPS);
                    b.triangle(rowA[s], rowC[s], rowC[s + 1], out);
                    b.triangle(rowA[s], rowC[s + 1], rowA[s + 1], out);
                }
            }
        }

        // 3. Corner caps: spherical pentagon of the five face normals around each kernel vertex,
        //    fanned from the vertex direction and subdivided so its rim matches the fillets.
        for (int v = 0; v < 12; v++) {
            Vector3f centre = new Vector3f(kernel[v]).normalize();
            List<Vector3f> ring = new ArrayList<>();
            for (Face f : faces) if (contains(f.verts, v)) ring.add(f.normal);
            Vector3f ref = new Vector3f(ring.get(0)).sub(new Vector3f(centre).mul(ring.get(0).dot(centre))).normalize();
            Vector3f ref2 = new Vector3f(centre).cross(ref);
            ring.sort(Comparator.comparingDouble(n -> Math.atan2(n.dot(ref2), n.dot(ref))));
            for (int s = 0; s < ring.size(); s++) {
                capTriangle(b, kernel[v], centre, ring.get(s), ring.get((s + 1) % ring.size()), blank);
            }
        }
        return b.build();
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
                Vector3f t = new Vector3f(d).cross(new Vector3f(0, 1, 0.37f)).normalize();
                Vector3f bt = new Vector3f(d).cross(t);
                grid[i][j] = b.vertex(new Vector3f(d).mul(EDGE_RADIUS).add(origin), d, uv, t, bt);
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

    private static int[] sharedVerts(int[] a, int[] b) {
        int[] out = new int[2];
        int c = 0;
        for (int v : a) if (contains(b, v)) {
            if (c == 2) return null;
            out[c++] = v;
        }
        return c == 2 ? out : null;
    }

    private static Vector3f nlerp(Vector3f a, Vector3f b, float t) {
        return new Vector3f(a).lerp(b, t).normalize();
    }

    /**
     * Rotation that sets this die down on the face opposite {@code upNumber}, so {@code upNumber}
     * points straight up (+Y) with its numeral reading upright from a camera looking along +Z.
     *
     * @param yaw extra spin about +Y, in radians, applied after the numeral is aligned
     */
    Quaternionf restRotation(int upNumber, float yaw) {
        Face f = faces.get(upNumber - 1);
        Quaternionf q = new Quaternionf().rotationTo(f.normal, new Vector3f(0, 1, 0));
        Vector3f topDir = new Vector3f(kernel[f.top]).sub(new Vector3f(f.normal).mul(kernel[f.top].dot(f.normal)));
        q.transform(topDir);
        float angle = (float) Math.atan2(topDir.x, topDir.z);
        return new Quaternionf().rotationY(yaw - angle).mul(q);
    }
}
