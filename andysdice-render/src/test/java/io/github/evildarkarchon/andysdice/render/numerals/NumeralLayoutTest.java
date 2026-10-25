package io.github.evildarkarchon.andysdice.render.numerals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.Solid;
import io.github.evildarkarchon.andysdice.core.geometry.UpFace;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.joml.Quaterniond;
import org.joml.Vector2dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Where each Numeral sits on each face, checked without GL against ADR-0008 and spec Appendix A: one height per
 * Die Kind, the 6/9 underline, the top-read d4, the orientation rules, and the 0.45 mm clearance inside the
 * fillet. Expected directions are worked out here from the solid and the face table, not read back from the
 * layout's own frames.
 */
class NumeralLayoutTest {

    /** Spec Appendix A, to the one decimal it gives. */
    private static final Map<DieKind, Double> APPENDIX_A_HEIGHT_MM = Map.of(
            DieKind.D4, 4.3, DieKind.D6, 11.6, DieKind.D8, 7.9, DieKind.D10, 7.6,
            DieKind.PERCENTILE_D10, 5.4, DieKind.D12, 7.2, DieKind.D20, 4.2);

    /** Directions are compared to within a degree. */
    private static final double COS_1_DEGREE = Math.cos(Math.toRadians(1));

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void everyNumeralOfADieKindSharesItsAppendixAHeight(DieKind kind) {
        NumeralLayout layout = NumeralLayout.of(kind);

        assertThat(layout.numeralHeightMm()).isCloseTo(APPENDIX_A_HEIGHT_MM.get(kind), within(0.05));
        for (NumeralLayout.Face face : layout.faces()) {
            for (NumeralLayout.Placement p : face.placements()) {
                assertThat(p.heightMm()).as("%s on face %d", p.numeral(), face.index())
                        .isEqualTo(layout.numeralHeightMm());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void everyFaceShowsItsFaceTableNumeral(DieKind kind) {
        NumeralLayout layout = NumeralLayout.of(kind);

        assertThat(layout.faces()).hasSize(kind.faceCount());
        for (int f = 0; f < kind.faceCount(); f++) {
            NumeralLayout.Face face = layout.faces().get(f);
            assertThat(face.index()).isEqualTo(f);
            if (kind == DieKind.D4) {
                // Top-read: a face shows its three corners' Numerals, every one but its own name.
                List<String> expected = new ArrayList<>();
                for (int corner = 0; corner < 4; corner++) {
                    if (corner != f) {
                        expected.add(kind.numeral(corner));
                    }
                }
                assertThat(face.placements()).extracting(NumeralLayout.Placement::numeral)
                        .containsExactlyInAnyOrderElementsOf(expected);
            } else {
                assertThat(face.placements()).extracting(NumeralLayout.Placement::numeral)
                        .containsExactly(kind.numeral(f));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void onlyALoneSixOrNineIsUnderlinedAndNeverOnTheD4OrPercentileD10(DieKind kind) {
        boolean marksAny = kind != DieKind.D4 && kind != DieKind.PERCENTILE_D10;
        for (NumeralLayout.Face face : NumeralLayout.of(kind).faces()) {
            for (NumeralLayout.Placement p : face.placements()) {
                boolean sixOrNine = p.numeral().equals("6") || p.numeral().equals("9");
                assertThat(p.underlined()).as("%s %s", kind, p.numeral()).isEqualTo(marksAny && sixOrNine);
            }
        }
    }

    @Test
    void theD4IsReadFromTheTop() {
        DieKind kind = DieKind.D4;
        Solid solid = kind.solid();
        for (int down = 0; down < 4; down++) {
            // Lay face `down` on the table: its missing corner points up, and that is the reading.
            Quaterniond pose = new Quaterniond().rotationTo(solid.faces().get(down).normal(), new Vector3d(0, -1, 0));
            String reading = UpFace.read(kind, pose).numeral();
            assertThat(reading).isEqualTo(kind.numeral(down));

            for (NumeralLayout.Face face : NumeralLayout.of(kind).faces()) {
                if (face.index() == down) {
                    continue;
                }
                // Each standing face shows the reading at its top corner, upright.
                NumeralLayout.Placement top = face.placements().stream()
                        .max((a, b) -> Double.compare(world(pose, a.centre()).y, world(pose, b.centre()).y))
                        .orElseThrow();
                assertThat(top.numeral()).as("face %d's top Numeral", face.index()).isEqualTo(reading);
                Vector3d up = world(pose, top.up());
                Vector3d n = world(pose, face.normal());
                Vector3d uphill = new Vector3d(0, 1, 0).fma(-n.y, n).normalize();
                assertThat(up.dot(uphill)).as("upright on face %d", face.index()).isGreaterThan(COS_1_DEGREE);
            }
        }
    }

    @Test
    void d4NumeralsPointTheirTopsAtTheirCorners() {
        DieKind kind = DieKind.D4;
        for (NumeralLayout.Face face : NumeralLayout.of(kind).faces()) {
            for (NumeralLayout.Placement p : face.placements()) {
                int corner = Integer.parseInt(p.numeral()) - 1;
                // Corner k is the vertex face k lacks, the one opposite it: straight along its normal negated.
                Vector3d toward = new Vector3d(kind.solid().faces().get(corner).normal()).negate()
                        .mul(3 * kind.inradius()).sub(face.centre());
                assertThat(p.up().dot(inPlane(toward, face.normal()))).isGreaterThan(COS_1_DEGREE);
            }
        }
    }

    @Test
    void d6NumeralsSitWithTheirBaselineTowardTheLowestNeighbour() {
        DieKind kind = DieKind.D6;
        for (NumeralLayout.Face face : NumeralLayout.of(kind).faces()) {
            int lowest = kind.solid().neighbours(face.index()).stream()
                    .min((a, b) -> Integer.compare(Integer.parseInt(kind.numeral(a)), Integer.parseInt(kind.numeral(b))))
                    .orElseThrow();
            Vector3d down = inPlane(kind.solid().faces().get(lowest).normal(), face.normal());
            assertThat(onlyPlacement(face).up().dot(down)).isLessThan(-COS_1_DEGREE);
        }
    }

    @ParameterizedTest
    @EnumSource(value = DieKind.class, names = {"D8", "D10"})
    void d8AndD10NumeralsPointTheirTopsAtTheirOwnHalfsApex(DieKind kind) {
        for (NumeralLayout.Face face : NumeralLayout.of(kind).faces()) {
            Vector3d apex = new Vector3d(0, 0, Math.signum(face.normal().z()));
            assertThat(onlyPlacement(face).up().dot(inPlane(apex, face.normal()))).isGreaterThan(COS_1_DEGREE);
        }
    }

    @Test
    void percentileNumeralsRunFromApexToEquator() {
        for (NumeralLayout.Face face : NumeralLayout.of(DieKind.PERCENTILE_D10).faces()) {
            Vector3d apex = inPlane(new Vector3d(0, 0, Math.signum(face.normal().z())), face.normal());
            NumeralLayout.Placement p = onlyPlacement(face);
            // Perpendicular to the d10's direction, the text running away from the apex.
            assertThat(Math.abs(p.up().dot(apex))).isLessThan(Math.sin(Math.toRadians(1)));
            assertThat(p.right().dot(apex)).isLessThan(-COS_1_DEGREE);
        }
    }

    @Test
    void d20NumeralsFollowThePhotoAndPointTheRestAtTheirNeighbourNearestTheTwenty() {
        DieKind kind = DieKind.D20;
        int twenty = 19;
        int eight = 7;
        List<Integer> besideTwenty = kind.solid().neighbours(twenty);
        assertThat(besideTwenty).extracting(kind::numeral).containsExactlyInAnyOrder("2", "8", "14");
        NumeralLayout layout = NumeralLayout.of(kind);

        // The 20's baseline faces the 8; the 2, 8 and 14 have their baselines toward the 20.
        assertBaselineToward(kind, layout.faces().get(twenty), eight);
        for (int f : besideTwenty) {
            assertBaselineToward(kind, layout.faces().get(f), twenty);
        }
        for (NumeralLayout.Face face : layout.faces()) {
            if (face.index() != twenty && !besideTwenty.contains(face.index())) {
                assertTopTowardNeighbourNearest(kind, face, twenty);
            }
        }
    }

    @Test
    void d12NumeralsPutAnUprightNineBelowTheTwelveAndPointTheRestAtTheirNeighbourNearestTheTwelve() {
        DieKind kind = DieKind.D12;
        int twelve = 11;
        int nine = 8;
        NumeralLayout layout = NumeralLayout.of(kind);

        assertBaselineToward(kind, layout.faces().get(twelve), nine);
        for (NumeralLayout.Face face : layout.faces()) {
            if (face.index() != twelve) {
                assertTopTowardNeighbourNearest(kind, face, twelve);
            }
        }
        // So the 9 points its top at the 12: upright below it.
        assertThat(kind.solid().neighbours(nine)).contains(twelve);
    }

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void everyNumeralFitsItsFaceClearOfTheFillet(DieKind kind) {
        for (NumeralLayout.Face face : NumeralLayout.of(kind).faces()) {
            List<Vector2dc> polygon = face.polygon();
            for (NumeralLayout.Placement p : face.placements()) {
                PathIterator it = p.outline().getPathIterator(null, 0.002);
                double[] c = new double[6];
                while (!it.isDone()) {
                    if (it.currentSegment(c) != PathIterator.SEG_CLOSE) {
                        assertThat(insetDistance(polygon, c[0], c[1])).as("%s %s", kind, p.numeral())
                                .isGreaterThanOrEqualTo(NumeralLayout.MARGIN_MM - 1e-6);
                    }
                    it.next();
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void faceFramesLieInTheFlatFaceOfTheRoundedSolid(DieKind kind) {
        for (NumeralLayout.Face face : NumeralLayout.of(kind).faces()) {
            Vector3dc n = kind.solid().faces().get(face.index()).normal();
            assertThat(face.normal().dot(n)).isCloseTo(1, within(1e-9));
            // The flat faces lie on the sharp solid's face planes (the kernel's pushed out by the edge radius), to
            // within the 4-decimal face tables: the solid builder averages corners whose planes just miss.
            assertThat(face.centre().dot(n)).isCloseTo(kind.inradius(), within(1e-4));
            assertThat(face.up().dot(n)).isCloseTo(0, within(1e-9));
            assertThat(face.right().dot(n)).isCloseTo(0, within(1e-9));
            // Right-handed seen from outside: right × up is the outward normal.
            assertThat(face.right().cross(face.up(), new Vector3d()).dot(n)).isCloseTo(1, within(1e-9));
        }
    }

    /** The distance of a point inside a convex CCW polygon from its nearest edge; negative outside. */
    private static double insetDistance(List<Vector2dc> polygon, double x, double y) {
        double least = Double.POSITIVE_INFINITY;
        for (int i = 0; i < polygon.size(); i++) {
            Vector2dc p = polygon.get(i);
            Vector2dc q = polygon.get((i + 1) % polygon.size());
            double ex = q.x() - p.x();
            double ey = q.y() - p.y();
            double len = Math.hypot(ex, ey);
            // Left of a CCW edge is inside.
            least = Math.min(least, (ex * (y - p.y()) - ey * (x - p.x())) / len);
        }
        return least;
    }

    private static void assertBaselineToward(DieKind kind, NumeralLayout.Face face, int other) {
        Vector3d toward = inPlane(kind.solid().faces().get(other).normal(), face.normal());
        assertThat(onlyPlacement(face).up().dot(toward)).as("%s %s baseline toward %s", kind,
                kind.numeral(face.index()), kind.numeral(other)).isLessThan(-COS_1_DEGREE);
    }

    /** Top at the edge shared with the neighbour nearest the top face; ties (the bottom face) take any of them. */
    private static void assertTopTowardNeighbourNearest(DieKind kind, NumeralLayout.Face face, int top) {
        Vector3dc topNormal = kind.solid().faces().get(top).normal();
        List<Integer> neighbours = kind.solid().neighbours(face.index());
        double nearest = neighbours.stream()
                .mapToDouble(g -> kind.solid().faces().get(g).normal().dot(topNormal)).max().orElseThrow();
        boolean pointsAtOne = false;
        for (int g : neighbours) {
            if (kind.solid().faces().get(g).normal().dot(topNormal) > nearest - 1e-4) {
                // On a regular face the shared edge's midpoint lies straight toward the neighbour's normal.
                Vector3d toward = inPlane(kind.solid().faces().get(g).normal(), face.normal());
                pointsAtOne |= onlyPlacement(face).up().dot(toward) > COS_1_DEGREE;
            }
        }
        assertThat(pointsAtOne).as("%s %s top toward its neighbour nearest %s", kind, kind.numeral(face.index()),
                kind.numeral(top)).isTrue();
    }

    private static NumeralLayout.Placement onlyPlacement(NumeralLayout.Face face) {
        assertThat(face.placements()).hasSize(1);
        return face.placements().getFirst();
    }

    private static Vector3d inPlane(Vector3dc d, Vector3dc n) {
        return new Vector3d(d).fma(-d.dot(n), n).normalize();
    }

    private static Vector3d world(Quaterniond pose, Vector3dc v) {
        return pose.transform(v, new Vector3d());
    }
}
