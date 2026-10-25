package io.github.evildarkarchon.andysdice.core.geometry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The sharp solid of each Die Kind, built from its face table: its topology (research §2.1, §2.2) and its
 * size (spec Appendix A).
 */
class SolidTest {

    /**
     * How far, in mm, a vertex may sit off a face plane. The research tables carry four decimals, so the d10's
     * five apex planes miss one point by about 1e-4 of the die's size; 10 µm is far below anything visible.
     */
    static final double PLANE_TOLERANCE_MM = 0.01;

    /** Research §2.1 and §2.2: vertices, faces and edges of each solid. */
    @ParameterizedTest
    @CsvSource({
        "D4, 4, 4, 6",
        "D6, 8, 6, 12",
        "D8, 6, 8, 12",
        "D10, 12, 10, 20",
        "PERCENTILE_D10, 12, 10, 20",
        "D12, 20, 12, 30",
        "D20, 12, 20, 30",
    })
    void hasTheTopologyOfItsPolyhedron(DieKind kind, int vertices, int faces, int edges) {
        Solid solid = kind.solid();

        assertThat(solid.vertices()).hasSize(vertices);
        assertThat(solid.faces()).hasSize(faces);
        assertThat(edges(solid)).hasSize(edges);
    }

    /** Every face plane lies at the Die Kind's inradius, and every vertex is inside every other face plane. */
    @ParameterizedTest
    @EnumSource(DieKind.class)
    void everyFacePlaneLiesAtTheInradius(DieKind kind) {
        Solid solid = kind.solid();

        assertThat(solid.inradius()).isEqualTo(kind.inradius());
        for (Solid.Face face : solid.faces()) {
            assertThat(face.normal().length()).isCloseTo(1, within(1e-12));
            for (int v : face.ring()) {
                assertThat(face.normal().dot(solid.vertices().get(v)))
                        .as("vertex %d on face %d", v, face.index())
                        .isCloseTo(kind.inradius(), within(PLANE_TOLERANCE_MM));
            }
            for (Vector3dc vertex : solid.vertices()) {
                assertThat(face.normal().dot(vertex)).isLessThan(kind.inradius() + PLANE_TOLERANCE_MM);
            }
        }
    }

    /** Each face's ring runs counter-clockwise seen from outside the solid, so its winding agrees with its normal. */
    @ParameterizedTest
    @EnumSource(DieKind.class)
    void faceRingsRunCounterClockwiseFromOutside(DieKind kind) {
        Solid solid = kind.solid();
        for (Solid.Face face : solid.faces()) {
            List<Integer> ring = face.ring();
            for (int i = 0; i < ring.size(); i++) {
                Vector3dc a = solid.vertices().get(ring.get(i));
                Vector3dc b = solid.vertices().get(ring.get((i + 1) % ring.size()));
                Vector3dc c = solid.vertices().get(ring.get((i + 2) % ring.size()));
                double turn = b.sub(a, new Vector3d()).cross(c.sub(b, new Vector3d())).dot(face.normal());
                assertThat(turn).as("turn at vertex %d of face %d", ring.get((i + 1) % ring.size()), face.index())
                        .isPositive();
            }
        }
    }

    /**
     * Research §2.2: the d10 (and so the percentile d10) is the sphere-variant pentagonal trapezohedron. Its kites
     * have angles 51.83° at the apex, 90° at the two sides and 128.17° at the equator, and it is 1.4953 times as
     * long tip to tip as face to face, with its odd apex at +z.
     */
    @ParameterizedTest
    @CsvSource({"D10", "PERCENTILE_D10"})
    void d10IsTheSphereVariantTrapezohedron(DieKind kind) {
        Solid solid = kind.solid();
        for (Solid.Face face : solid.faces()) {
            List<Integer> ring = face.ring();
            assertThat(ring).hasSize(4);
            List<Double> angles = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Vector3dc prev = solid.vertices().get(ring.get((i + 3) % 4));
                Vector3dc at = solid.vertices().get(ring.get(i));
                Vector3dc next = solid.vertices().get(ring.get((i + 1) % 4));
                angles.add(Math.toDegrees(prev.sub(at, new Vector3d()).angle(next.sub(at, new Vector3d()))));
            }
            angles.sort(null);
            assertThat(angles.get(0)).isCloseTo(51.83, within(0.05));
            assertThat(angles.get(1)).isCloseTo(90, within(0.05));
            assertThat(angles.get(2)).isCloseTo(90, within(0.05));
            assertThat(angles.get(3)).isCloseTo(128.17, within(0.05));
        }
        double tip = 1.4953 * kind.inradius();
        assertThat(solid.vertices()).anySatisfy(v -> assertThat(v.distance(0, 0, tip)).isLessThan(0.01));
        assertThat(solid.vertices()).anySatisfy(v -> assertThat(v.distance(0, 0, -tip)).isLessThan(0.01));
        // The odd faces meet at the +z apex.
        for (Solid.Face face : solid.faces()) {
            assertThat(face.normal().z() > 0).isEqualTo(Integer.parseInt(DieKind.D10.numeral(face.index())) % 2 == 1);
        }
    }

    /** The percentile d10 reuses the d10's solid rather than building a copy (spec §3.3). */
    @Test
    void percentileD10SharesTheD10Solid() {
        assertThat(DieKind.PERCENTILE_D10.solid()).isSameAs(DieKind.D10.solid());
    }

    /** The undirected edges of a solid, each as the sorted pair of its vertex indices. */
    private static Set<List<Integer>> edges(Solid solid) {
        Set<List<Integer>> edges = new HashSet<>();
        for (Solid.Face face : solid.faces()) {
            List<Integer> ring = face.ring();
            for (int i = 0; i < ring.size(); i++) {
                int a = ring.get(i);
                int b = ring.get((i + 1) % ring.size());
                edges.add(List.of(Math.min(a, b), Math.max(a, b)));
            }
        }
        return edges;
    }
}
