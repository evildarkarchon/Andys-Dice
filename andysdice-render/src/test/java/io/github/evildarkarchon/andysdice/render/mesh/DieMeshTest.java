package io.github.evildarkarchon.andysdice.render.mesh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.Solid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The rounded Die meshes, checked without GL (ADR-0005): each is a closed, consistently wound surface whose
 * normals are those of the Minkowski sum of the kernel solid and a 0.75 mm sphere, and whose flat faces are the
 * Die Kind's face table, face for face.
 */
class DieMeshTest {

    /** Lengths in mm; the meshes are floats, so a few float ulps at 10 mm. */
    private static final double MM = 1e-4;

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void isWatertightAndConsistentlyWound(DieKind kind) {
        DieMesh mesh = DieMesh.of(kind);
        Welded welded = Welded.of(mesh);

        // Every directed edge appears once, and its reverse once: a closed, oriented 2-manifold.
        Map<Long, Integer> directed = new HashMap<>();
        for (int t = 0; t < mesh.triangleCount(); t++) {
            for (int k = 0; k < 3; k++) {
                int p = welded.id(mesh.index(t, k));
                int q = welded.id(mesh.index(t, (k + 1) % 3));
                assertThat(p).as("triangle %d is degenerate", t).isNotEqualTo(q);
                directed.merge(edge(p, q), 1, Integer::sum);
            }
        }
        for (Map.Entry<Long, Integer> e : directed.entrySet()) {
            assertThat(e.getValue()).as("directed edge used once").isEqualTo(1);
            long key = e.getKey();
            long reverse = edge((int) (key & 0xffffffffL), (int) (key >>> 32));
            assertThat(directed).as("every edge has its twin").containsKey(reverse);
        }
        // A sphere's topology: V - E + F = 2.
        int edges = directed.size() / 2;
        assertThat(welded.count() - edges + mesh.triangleCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void normalsAreThoseOfTheRoundedSolid(DieKind kind) {
        DieMesh mesh = DieMesh.of(kind);
        Solid kernel = Solid.ofHalfSpaces(normals(kind.solid()), kind.inradius() - kind.edgeRadius());

        for (int v = 0; v < mesh.vertexCount(); v++) {
            Vector3f p = mesh.position(v, new Vector3f());
            Vector3f n = mesh.normal(v, new Vector3f());
            assertThat(n.length()).as("unit normal").isCloseTo(1f, within(1e-5f));
            // A point of the Minkowski sum K + rB with normal n lies at the kernel's support distance plus r.
            double support = Double.NEGATIVE_INFINITY;
            for (Vector3dc k : kernel.vertices()) {
                support = Math.max(support, n.x * k.x() + n.y * k.y() + n.z * k.z());
            }
            assertThat((double) n.dot(p)).as("vertex %d", v).isCloseTo(support + kind.edgeRadius(), within(MM));
        }
    }

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void trianglesFaceTheWayTheirNormalsPointAndNormalsAreContinuous(DieKind kind) {
        DieMesh mesh = DieMesh.of(kind);
        for (int t = 0; t < mesh.triangleCount(); t++) {
            Vector3f a = mesh.position(mesh.index(t, 0), new Vector3f());
            Vector3f b = mesh.position(mesh.index(t, 1), new Vector3f());
            Vector3f c = mesh.position(mesh.index(t, 2), new Vector3f());
            Vector3f geometric = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
            assertThat(geometric.length()).as("triangle %d has area", t).isGreaterThan(0f);
            geometric.normalize();
            for (int k = 0; k < 3; k++) {
                Vector3f n = mesh.normal(mesh.index(t, k), new Vector3f());
                assertThat(geometric.dot(n)).as("triangle %d is wound counter-clockwise from outside", t)
                        .isGreaterThan(0.5f);
            }
        }
        // Where patches meet, every copy of a position carries the same normal: the surface is smooth.
        Map<List<Float>, Vector3f> normalAt = new HashMap<>();
        for (int v = 0; v < mesh.vertexCount(); v++) {
            Vector3f p = mesh.position(v, new Vector3f());
            Vector3f n = mesh.normal(v, new Vector3f());
            Vector3f seen = normalAt.putIfAbsent(List.of(p.x, p.y, p.z), n);
            if (seen != null) {
                assertThat(seen.distance(n)).as("normal at shared vertex %d", v).isLessThan(1e-5f);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void flatFacesMatchTheFaceTable(DieKind kind) {
        DieMesh mesh = DieMesh.of(kind);
        Solid sharp = kind.solid();
        Set<Integer> seen = new HashSet<>();
        for (int f = 0; f < kind.faceCount(); f++) {
            Solid.Face face = sharp.faces().get(f);
            Vector3dc normal = face.normal();
            List<Integer> vertices = new ArrayList<>();
            for (int v = 0; v < mesh.vertexCount(); v++) {
                if (mesh.face(v) == f) {
                    vertices.add(v);
                }
            }
            assertThat(vertices).as("face %d's corners", f).hasSize(face.ring().size());
            for (int v : vertices) {
                Vector3f n = mesh.normal(v, new Vector3f());
                Vector3f p = mesh.position(v, new Vector3f());
                assertThat(new Vector3d(n).distance(normal)).as("face %d normal", f).isLessThan(1e-6);
                // On the sharp solid's face plane, the Die's inradius out from its centre.
                assertThat(normal.dot(new Vector3d(p))).as("face %d plane", f)
                        .isCloseTo(kind.inradius(), within(2e-3));
                // Inside the sharp face: no further out along any other face's normal than its plane.
                for (Solid.Face other : sharp.faces()) {
                    assertThat(other.normal().dot(new Vector3d(p))).isLessThanOrEqualTo(kind.inradius() + 2e-3);
                }
            }
            int triangles = 0;
            for (int t = 0; t < mesh.triangleCount(); t++) {
                if (mesh.face(mesh.index(t, 0)) == f) {
                    triangles++;
                    for (int k = 1; k < 3; k++) {
                        assertThat(mesh.face(mesh.index(t, k))).as("a flat triangle stays on its face").isEqualTo(f);
                    }
                }
            }
            assertThat(triangles).as("face %d is one convex polygon, fanned", f).isEqualTo(face.ring().size() - 2);
            seen.add(f);
        }
        for (int v = 0; v < mesh.vertexCount(); v++) {
            assertThat(mesh.face(v)).isBetween(DieMesh.NO_FACE, kind.faceCount() - 1);
        }
        assertThat(seen).hasSize(kind.faceCount());
    }

    @Test
    void theFlatFacesOfACubeAreSquaresShrunkByTheEdgeRadius() {
        // A worked example: the d6 is a 16 mm cube, so each flat face is a (16 - 2 * 0.75) = 14.5 mm square.
        DieMesh mesh = DieMesh.of(DieKind.D6);
        for (int v = 0; v < mesh.vertexCount(); v++) {
            if (mesh.face(v) != DieMesh.NO_FACE) {
                Vector3f p = mesh.position(v, new Vector3f()).absolute();
                float[] sorted = {p.x, p.y, p.z};
                java.util.Arrays.sort(sorted);
                assertThat(sorted[0]).isCloseTo(7.25f, within(1e-4f));
                assertThat(sorted[1]).isCloseTo(7.25f, within(1e-4f));
                assertThat(sorted[2]).isCloseTo(8f, within(1e-4f));
            }
        }
    }

    @Test
    void theRoundedCubesVolumeIsTheMinkowskiSumsWithinTessellation() {
        // Kernel 14.5 mm cube + r = 0.75: V = a^3 + 6 a^2 r + 3 pi a r^2 + 4/3 pi r^3 (Steiner's formula).
        double a = 14.5;
        double r = 0.75;
        double exact = a * a * a + 6 * a * a * r + 3 * Math.PI * a * r * r + 4.0 / 3 * Math.PI * r * r * r;
        double volume = volume(DieMesh.of(DieKind.D6));
        // Chords cut inside the fillets and caps, so the mesh is slightly smaller, never larger.
        assertThat(volume).isLessThan(exact).isGreaterThan(exact - 2);
    }

    @Test
    void thePercentileD10SharesTheD10sMesh() {
        assertThat(DieMesh.of(DieKind.PERCENTILE_D10)).isSameAs(DieMesh.of(DieKind.D10));
        assertThat(DieMesh.of(DieKind.D20)).isSameAs(DieMesh.of(DieKind.D20));
    }

    private static double volume(DieMesh mesh) {
        double six = 0;
        for (int t = 0; t < mesh.triangleCount(); t++) {
            Vector3f a = mesh.position(mesh.index(t, 0), new Vector3f());
            Vector3f b = mesh.position(mesh.index(t, 1), new Vector3f());
            Vector3f c = mesh.position(mesh.index(t, 2), new Vector3f());
            six += new Vector3d(a).dot(new Vector3d(b).cross(new Vector3d(c)));
        }
        return six / 6;
    }

    private static List<Vector3dc> normals(Solid solid) {
        List<Vector3dc> out = new ArrayList<>();
        for (Solid.Face f : solid.faces()) {
            out.add(f.normal());
        }
        return out;
    }

    private static long edge(int p, int q) {
        return ((long) p << 32) | (q & 0xffffffffL);
    }

    /**
     * The mesh's vertices merged by exact position. Patches (faces, fillets, corners) keep their own vertices,
     * so the mesh is closed only if their shared boundaries agree bit for bit: no cracks for a rasteriser.
     */
    private record Welded(int[] ids, int count) {
        static Welded of(DieMesh mesh) {
            Map<List<Float>, Integer> byPosition = new HashMap<>();
            int[] ids = new int[mesh.vertexCount()];
            for (int v = 0; v < mesh.vertexCount(); v++) {
                Vector3f p = mesh.position(v, new Vector3f());
                ids[v] = byPosition.computeIfAbsent(List.of(p.x, p.y, p.z), key -> byPosition.size());
            }
            return new Welded(ids, byPosition.size());
        }

        int id(int vertex) {
            return ids[vertex];
        }
    }
}
