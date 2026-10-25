package io.github.evildarkarchon.andysdice.render.mesh;

import io.github.evildarkarchon.andysdice.core.geometry.Solid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Builds a {@link DieMesh}: flat faces, edge fillets and corner caps of the kernel-plus-sphere solid, after the
 * numerals prototype's {@code DieModel.buildMesh} (render.md), in doubles.
 *
 * <p>Every surface point is {@code kernelVertex + r · direction}, and each direction is a normalised integer
 * blend of face normals ({@link #blend}). Where two patches meet they compute the same blend of the same two
 * normals, and IEEE addition of two terms is commutative, so their shared vertices come out bit for bit equal:
 * that is what keeps the mesh closed without welding.
 */
final class RoundedMeshBuilder {

    private final List<Vector3dc> kernelVertices;
    private final double radius;
    private final int steps;
    private final List<Double> positions = new ArrayList<>();
    private final List<Double> normals = new ArrayList<>();
    private final List<Integer> faces = new ArrayList<>();
    private final List<Integer> indices = new ArrayList<>();

    private RoundedMeshBuilder(List<Vector3dc> kernelVertices, double radius, int steps) {
        this.kernelVertices = kernelVertices;
        this.radius = radius;
        this.steps = steps;
    }

    /**
     * Builds the rounded mesh of {@code solid}.
     *
     * @param solid the sharp solid
     * @param radius the edge radius, already checked to be within {@code (0, inradius)}
     * @param steps subdivisions across a fillet
     * @return the mesh
     * @throws IllegalStateException if the kernel doesn't keep every face of the solid
     */
    static DieMesh build(Solid solid, double radius, int steps) {
        List<Vector3dc> normals = new ArrayList<>();
        for (Solid.Face f : solid.faces()) {
            normals.add(f.normal());
        }
        Solid kernel = Solid.ofHalfSpaces(normals, solid.inradius() - radius);
        RoundedMeshBuilder b = new RoundedMeshBuilder(kernel.vertices(), radius, steps);
        b.flatFaces(kernel);
        b.fillets(kernel);
        b.caps(kernel);
        return b.toMesh();
    }

    /** Each kernel face pushed out by r along its normal: a convex polygon, fanned from its first corner. */
    private void flatFaces(Solid kernel) {
        for (Solid.Face f : kernel.faces()) {
            List<Integer> ring = f.ring();
            int[] corner = new int[ring.size()];
            for (int k = 0; k < ring.size(); k++) {
                corner[k] = vertex(ring.get(k), f.normal(), f.index());
            }
            for (int k = 1; k + 1 < ring.size(); k++) {
                triangle(corner[0], corner[k], corner[k + 1], f.normal());
            }
        }
    }

    /**
     * A quarter-cylinder-like strip along each kernel edge, sweeping the normal from one face's to the other's.
     * Only two vertices along the edge are needed: the fillet is straight that way.
     */
    private void fillets(Solid kernel) {
        for (Solid.Face face : kernel.faces()) {
            List<Integer> ring = face.ring();
            List<Integer> neighbours = kernel.neighbours(face.index());
            for (int k = 0; k < ring.size(); k++) {
                int other = neighbours.get(k);
                // Each edge once, from the lower-numbered face's side.
                if (other < face.index()) {
                    continue;
                }
                Vector3dc ni = face.normal();
                Vector3dc nj = kernel.faces().get(other).normal();
                int a = ring.get(k);
                int c = ring.get((k + 1) % ring.size());
                int[] rowA = new int[steps + 1];
                int[] rowC = new int[steps + 1];
                for (int s = 0; s <= steps; s++) {
                    Vector3dc d = blend(ni, steps - s, nj, s, null, 0);
                    rowA[s] = vertex(a, d, DieMesh.NO_FACE);
                    rowC[s] = vertex(c, d, DieMesh.NO_FACE);
                }
                for (int s = 0; s < steps; s++) {
                    Vector3dc out = new Vector3d(ni).add(nj);
                    triangle(rowA[s], rowC[s], rowC[s + 1], out);
                    triangle(rowA[s], rowC[s + 1], rowA[s + 1], out);
                }
            }
        }
    }

    /**
     * A spherical polygon at each kernel corner, spanned by the normals of the faces meeting there: fanned from
     * their mean direction into triangles (centre, n1, n2), each subdivided so its rim matches the fillets.
     */
    private void caps(Solid kernel) {
        for (int v = 0; v < kernelVertices.size(); v++) {
            List<Vector3dc> around = new ArrayList<>();
            for (Solid.Face f : kernel.faces()) {
                if (f.ring().contains(v)) {
                    around.add(f.normal());
                }
            }
            Vector3d centre = new Vector3d();
            for (Vector3dc n : around) {
                centre.add(n);
            }
            centre.normalize();
            // Order the normals round the centre; on a convex solid, neighbours in this order share an edge.
            Vector3d e1 = new Vector3d(around.getFirst()).fma(-around.getFirst().dot(centre), centre).normalize();
            Vector3d e2 = centre.cross(e1, new Vector3d());
            around.sort(Comparator.comparingDouble(n -> Math.atan2(n.dot(e2), n.dot(e1))));
            for (int k = 0; k < around.size(); k++) {
                capTriangle(v, centre, around.get(k), around.get((k + 1) % around.size()));
            }
        }
    }

    /** The spherical triangle (centre, n1, n2) about kernel corner {@code v}, split into steps² triangles. */
    private void capTriangle(int v, Vector3dc centre, Vector3dc n1, Vector3dc n2) {
        int[][] grid = new int[steps + 1][];
        for (int i = 0; i <= steps; i++) {
            grid[i] = new int[steps + 1 - i];
            for (int j = 0; j <= steps - i; j++) {
                grid[i][j] = vertex(v, blend(n1, i, n2, j, centre, steps - i - j), DieMesh.NO_FACE);
            }
        }
        for (int i = 0; i < steps; i++) {
            for (int j = 0; j < steps - i; j++) {
                Vector3dc out = blend(n1, 3 * i + 1, n2, 3 * j + 1, centre, 3 * (steps - i - j) - 2);
                triangle(grid[i][j], grid[i + 1][j], grid[i][j + 1], out);
                if (j < steps - i - 1) {
                    triangle(grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1], out);
                }
            }
        }
    }

    /**
     * The unit direction {@code normalise(wa·a + wb·b + wc·c)}. A term of weight 0 is left out rather than added
     * as zero, and a lone term is returned unnormalised (face normals are already unit), so that a blend of the
     * same two normals with the same weights is bit for bit the same whichever patch asks for it. Weights are
     * integers out of a common total, so the fractions are the same doubles everywhere too.
     */
    private Vector3dc blend(Vector3dc a, int wa, Vector3dc b, int wb, Vector3dc c, int wc) {
        int total = wa + wb + wc;
        int terms = (wa > 0 ? 1 : 0) + (wb > 0 ? 1 : 0) + (wc > 0 ? 1 : 0);
        if (terms == 1) {
            return wa > 0 ? a : wb > 0 ? b : c;
        }
        double x = 0;
        double y = 0;
        double z = 0;
        Vector3dc[] dirs = {a, b, c};
        int[] weights = {wa, wb, wc};
        for (int t = 0; t < 3; t++) {
            if (weights[t] > 0) {
                double w = weights[t] / (double) total;
                x += w * dirs[t].x();
                y += w * dirs[t].y();
                z += w * dirs[t].z();
            }
        }
        double length = Math.sqrt(x * x + y * y + z * z);
        return new Vector3d(x / length, y / length, z / length);
    }

    /** Adds the surface point {@code kernelVertex + r · direction}, with that direction as its normal. */
    private int vertex(int kernelVertex, Vector3dc direction, int face) {
        Vector3dc k = kernelVertices.get(kernelVertex);
        positions.add(k.x() + radius * direction.x());
        positions.add(k.y() + radius * direction.y());
        positions.add(k.z() + radius * direction.z());
        normals.add(direction.x());
        normals.add(direction.y());
        normals.add(direction.z());
        faces.add(face);
        return faces.size() - 1;
    }

    /** Adds a triangle, flipped if need be so that it winds counter-clockwise seen from {@code outward}. */
    private void triangle(int p, int q, int r, Vector3dc outward) {
        Vector3d pq = point(q).sub(point(p));
        Vector3d pr = point(r).sub(point(p));
        boolean flip = pq.cross(pr).dot(outward) < 0;
        indices.add(p);
        indices.add(flip ? r : q);
        indices.add(flip ? q : r);
    }

    private Vector3d point(int v) {
        return new Vector3d(positions.get(3 * v), positions.get(3 * v + 1), positions.get(3 * v + 2));
    }

    private DieMesh toMesh() {
        float[] p = new float[positions.size()];
        float[] n = new float[normals.size()];
        for (int i = 0; i < p.length; i++) {
            p[i] = (float) (double) positions.get(i);
            n[i] = (float) (double) normals.get(i);
        }
        return new DieMesh(p, n, faces.stream().mapToInt(Integer::intValue).toArray(),
                indices.stream().mapToInt(Integer::intValue).toArray());
    }
}
