package io.github.evildarkarchon.andysdice.core.geometry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Each Die Kind's symmetry group: the proper rotations that map its solid onto itself, derived from the solid
 * rather than written down (ticket #31, ADR-0005).
 */
class SymmetryTest {

    /**
     * How far, in mm, a rotated vertex may land from a vertex of the solid. The face tables carry four decimals,
     * so a derived rotation is off by about 1e-4 rad; at a 10 mm die that is about 1 µm.
     */
    static final double VERTEX_TOLERANCE_MM = 0.01;

    /**
     * The orders of the rotation groups, known independently of the code: the tetrahedral group A4 (12), the
     * octahedral group (24) for the cube and octahedron, the icosahedral group (60) for the dodecahedron and
     * icosahedron, and the pentagonal trapezohedron's dihedral group D5 (10).
     */
    @ParameterizedTest
    @CsvSource({
        "D4, 12",
        "D6, 24",
        "D8, 24",
        "D10, 10",
        "PERCENTILE_D10, 10",
        "D12, 60",
        "D20, 60",
    })
    void hasTheRotationGroupOfItsPolyhedron(DieKind kind, int order) {
        assertThat(kind.symmetries()).hasSize(order);
    }

    /** Every symmetry is a distinct permutation of the faces, and the identity comes first. */
    @ParameterizedTest
    @EnumSource(DieKind.class)
    void permutesTheFacesDistinctlyStartingWithTheIdentity(DieKind kind) {
        List<Symmetry> group = kind.symmetries();
        Set<List<Integer>> seen = new HashSet<>();
        for (Symmetry s : group) {
            assertThat(new HashSet<>(s.faceMap())).hasSize(kind.faceCount());
            assertThat(seen.add(s.faceMap())).as("duplicate %s", s.faceMap()).isTrue();
        }
        Symmetry identity = group.getFirst();
        for (int f = 0; f < kind.faceCount(); f++) {
            assertThat(identity.face(f)).isEqualTo(f);
        }
        assertThat(identity.rotation().angle()).isCloseTo(0, within(1e-9));
    }

    /**
     * Each symmetry's rotation takes face {@code f}'s normal onto face {@code face(f)}'s, and takes every vertex
     * onto a vertex: it maps the solid onto itself.
     */
    @ParameterizedTest
    @EnumSource(DieKind.class)
    void everySymmetryMapsTheSolidOntoItself(DieKind kind) {
        Solid solid = kind.solid();
        for (Symmetry s : kind.symmetries()) {
            assertMapsSolidOntoItself(solid, s);
        }
    }

    /** The percentile d10 shares the d10's solid, so it shares its group too. */
    @Test
    void percentileD10SharesTheD10Group() {
        assertThat(DieKind.PERCENTILE_D10.symmetries()).isSameAs(DieKind.D10.symmetries());
    }

    /**
     * Asserts that {@code s} carries every face normal of {@code solid} onto the normal of the face it names,
     * and every vertex onto some vertex.
     */
    static void assertMapsSolidOntoItself(Solid solid, Symmetry s) {
        Quaterniond q = new Quaterniond(s.rotation());
        assertThat(q.lengthSquared()).isCloseTo(1, within(1e-9));
        for (Solid.Face face : solid.faces()) {
            Vector3d moved = q.transform(face.normal(), new Vector3d());
            Vector3dc target = solid.faces().get(s.face(face.index())).normal();
            assertThat(moved.distance(target))
                    .as("face %d onto face %d", face.index(), s.face(face.index()))
                    .isLessThan(1e-3);
        }
        for (Vector3dc v : solid.vertices()) {
            Vector3d moved = q.transform(v, new Vector3d());
            double nearest = solid.vertices().stream().mapToDouble(moved::distance).min().orElseThrow();
            assertThat(nearest).as("vertex %s", v).isLessThan(VERTEX_TOLERANCE_MM);
        }
    }
}
