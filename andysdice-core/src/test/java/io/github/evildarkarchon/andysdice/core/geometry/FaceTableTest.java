package io.github.evildarkarchon.andysdice.core.geometry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Each Die Kind's numbering against the real-dice-geometry research (§3): the Numerals, the opposite-face
 * sums, chirality and every published neighbour table. The neighbour tables below are the research's own,
 * written as Numerals, so they check the transcribed normals independently of how they were copied.
 */
class FaceTableTest {

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
        "D4; 1 2 3 4",
        "D6; 1 2 3 4 5 6",
        "D8; 1 2 3 4 5 6 7 8",
        "D10; 0 1 2 3 4 5 6 7 8 9",
        "PERCENTILE_D10; 00 10 20 30 40 50 60 70 80 90",
        "D12; 1 2 3 4 5 6 7 8 9 10 11 12",
        "D20; 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20",
    })
    void numeralsRunInFaceTableOrder(DieKind kind, String numerals) {
        List<String> expected = List.of(numerals.split(" "));

        assertThat(kind.faceCount()).isEqualTo(expected.size());
        assertThat(IntStream.range(0, kind.faceCount()).mapToObj(kind::numeral).toList()).isEqualTo(expected);
    }

    /** Research §3.1: opposite faces sum to N + 1 (the 0–9 d10 to 9; the percentile d10's tens to 90). */
    @ParameterizedTest
    @CsvSource({"D6, 7", "D8, 9", "D10, 9", "PERCENTILE_D10, 90", "D12, 13", "D20, 21"})
    void oppositeFacesSumToTheKindsTotal(DieKind kind, int sum) {
        List<Solid.Face> faces = kind.solid().faces();
        for (Solid.Face face : faces) {
            Solid.Face opposite = faces.stream()
                    .filter(other -> other.normal().dot(face.normal()) < -0.9999)
                    .findFirst()
                    .orElseThrow();
            assertThat(Integer.parseInt(kind.numeral(face.index())) + Integer.parseInt(kind.numeral(opposite.index())))
                    .as("face %s and its opposite %s", kind.numeral(face.index()), kind.numeral(opposite.index()))
                    .isEqualTo(sum);
        }
    }

    /** Research §3.3: the d6 is right-handed, 6 = +z, 1 = −z, 3 = +y, 4 = −y, 2 = −x, 5 = +x. */
    @Test
    void d6IsTheRightHandedCube() {
        List<Solid.Face> faces = DieKind.D6.solid().faces();
        assertThat(normalOf(DieKind.D6, "6").distance(new Vector3d(0, 0, 1))).isLessThan(1e-12);
        assertThat(normalOf(DieKind.D6, "1").distance(new Vector3d(0, 0, -1))).isLessThan(1e-12);
        assertThat(normalOf(DieKind.D6, "3").distance(new Vector3d(0, 1, 0))).isLessThan(1e-12);
        assertThat(normalOf(DieKind.D6, "4").distance(new Vector3d(0, -1, 0))).isLessThan(1e-12);
        assertThat(normalOf(DieKind.D6, "2").distance(new Vector3d(-1, 0, 0))).isLessThan(1e-12);
        assertThat(normalOf(DieKind.D6, "5").distance(new Vector3d(1, 0, 0))).isLessThan(1e-12);
        // n1 · (n2 × n3) = +1: 1 → 2 → 3 runs counter-clockwise from outside.
        assertThat(faces.get(0).normal().dot(faces.get(1).normal().cross(faces.get(2).normal(), new Vector3d())))
                .isCloseTo(1, within(1e-12));
    }

    /**
     * Research §3.2: the d4 is right-handed. Seen from outside, corners 1-2-3 and 1-3-4 run clockwise and 1-2-4
     * and 2-3-4 counter-clockwise; each face is named by the corner it lacks, which points up when it lies on
     * the table.
     */
    @Test
    void d4IsRightHandedAndEachFaceLacksItsOwnCorner() {
        assertThat(cornerTurn("1", "2", "3")).isNegative();
        assertThat(cornerTurn("1", "3", "4")).isNegative();
        assertThat(cornerTurn("1", "2", "4")).isPositive();
        assertThat(cornerTurn("2", "3", "4")).isPositive();
        for (Solid.Face face : DieKind.D4.solid().faces()) {
            // The corner opposite a face is the one vertex not on it, straight along the negated normal.
            Vector3dc corner = cornerOf(DieKind.D4.numeral(face.index()));
            assertThat(face.ring()).doesNotContain(DieKind.D4.solid().vertices().indexOf(corner));
            assertThat(corner.normalize(new Vector3d()).dot(face.normal())).isCloseTo(-1, within(1e-12));
        }
    }

    /** The research's neighbour tables (§3.4–§3.7): each face's neighbours, counter-clockwise from outside. */
    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
        // d8, research §3.4
        "D8; 1; 3 4 7", "D8; 2; 3 8 4", "D8; 3; 1 5 2", "D8; 4; 1 2 6",
        "D8; 5; 3 7 8", "D8; 6; 4 8 7", "D8; 7; 1 6 5", "D8; 8; 2 5 6",
        // d10, research §3.5
        "D10; 0; 3 7 4 8", "D10; 1; 4 7 9 6", "D10; 2; 5 8 6 9", "D10; 3; 0 8 5 7", "D10; 4; 0 7 1 6",
        "D10; 5; 2 9 3 8", "D10; 6; 1 9 2 4", "D10; 7; 0 3 1 4", "D10; 8; 0 2 5 3", "D10; 9; 1 5 2 6",
        // the percentile d10 is the d10 with its Numerals ×10 (research §3.5)
        "PERCENTILE_D10; 00; 30 70 40 80", "PERCENTILE_D10; 90; 10 50 20 60",
        // d12, research §3.6
        "D12; 1; 2 10 5 6 4", "D12; 2; 1 4 8 7 10", "D12; 3; 4 6 11 12 8", "D12; 4; 1 6 3 8 2",
        "D12; 5; 1 10 9 11 6", "D12; 6; 1 5 11 3 4", "D12; 7; 2 8 12 9 10", "D12; 8; 2 4 3 12 7",
        "D12; 9; 5 10 7 12 11", "D12; 10; 1 2 7 9 5", "D12; 11; 3 6 5 9 12", "D12; 12; 3 11 9 7 8",
        // d20, research §3.7
        "D20; 1; 7 13 19", "D20; 2; 12 20 18", "D20; 3; 16 17 19", "D20; 4; 11 18 14", "D20; 5; 13 15 18",
        "D20; 6; 9 14 16", "D20; 7; 1 17 15", "D20; 8; 10 16 20", "D20; 9; 6 19 11", "D20; 10; 8 12 17",
        "D20; 11; 4 9 13", "D20; 12; 2 15 10", "D20; 13; 1 5 11", "D20; 14; 4 20 6", "D20; 15; 5 7 12",
        "D20; 16; 3 6 8", "D20; 17; 3 10 7", "D20; 18; 2 4 5", "D20; 19; 1 9 3", "D20; 20; 2 8 14",
    })
    void neighboursMatchTheResearchCounterClockwiseFromOutside(DieKind kind, String face, String neighbours) {
        List<String> expected = List.of(neighbours.split(" "));
        List<String> actual = kind.solid().neighbours(indexOf(kind, face)).stream().map(kind::numeral).toList();

        assertThat(rotatedToStartWith(actual, expected.getFirst())).isEqualTo(expected);
    }

    /** Research §3.7: the five faces round each d20 vertex sum to 41, 44, 47, 51, 52, 52, 53, 53, 54, 58, 61, 64. */
    @Test
    void d20VertexSumsMatchTheResearch() {
        Solid solid = DieKind.D20.solid();
        List<Integer> sums = new ArrayList<>();
        for (int v = 0; v < solid.vertices().size(); v++) {
            int vertex = v;
            sums.add(solid.faces().stream()
                    .filter(face -> face.ring().contains(vertex))
                    .mapToInt(face -> Integer.parseInt(DieKind.D20.numeral(face.index())))
                    .sum());
        }
        assertThat(sums).containsExactlyInAnyOrder(41, 44, 47, 51, 52, 52, 53, 53, 54, 58, 61, 64);
    }

    /** Research §3.5: the d10's 9 face, as a spot check of the transcribed normals. */
    @Test
    void d10NineFaceHasTheResearchNormal() {
        assertThat(normalOf(DieKind.D10, "9").equals(new Vector3d(0.6015, -0.4370, 0.6687).normalize(), 1e-12))
                .isTrue();
    }

    private static int indexOf(DieKind kind, String numeral) {
        return IntStream.range(0, kind.faceCount())
                .filter(i -> kind.numeral(i).equals(numeral))
                .findFirst()
                .orElseThrow();
    }

    private static Vector3dc normalOf(DieKind kind, String numeral) {
        return kind.solid().faces().get(indexOf(kind, numeral)).normal();
    }

    /** The d4 vertex carrying corner label {@code numeral}: the one vertex not on the face of that name. */
    private static Vector3dc cornerOf(String numeral) {
        Solid solid = DieKind.D4.solid();
        List<Integer> ring = solid.faces().get(indexOf(DieKind.D4, numeral)).ring();
        return IntStream.range(0, solid.vertices().size())
                .filter(v -> !ring.contains(v))
                .mapToObj(solid.vertices()::get)
                .findFirst()
                .orElseThrow();
    }

    /**
     * The sense in which d4 corners {@code a → b → c} turn, seen from outside the face they span: positive is
     * counter-clockwise.
     */
    private static double cornerTurn(String a, String b, String c) {
        Vector3dc pa = cornerOf(a);
        Vector3dc pb = cornerOf(b);
        Vector3dc pc = cornerOf(c);
        Vector3d outward = new Vector3d(pa).add(pb).add(pc);
        return pb.sub(pa, new Vector3d()).cross(pc.sub(pa, new Vector3d())).dot(outward);
    }

    private static List<String> rotatedToStartWith(List<String> cycle, String first) {
        int at = cycle.indexOf(first);
        assertThat(at).as("%s in %s", first, cycle).isNotNegative();
        List<String> out = new ArrayList<>(cycle.subList(at, cycle.size()));
        out.addAll(cycle.subList(0, at));
        return out;
    }
}
