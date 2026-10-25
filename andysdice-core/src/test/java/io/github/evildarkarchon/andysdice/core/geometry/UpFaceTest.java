package io.github.evildarkarchon.andysdice.core.geometry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Reading a Die's pose: which face is up, and whether the Die is cocked (more than 4° from flat; spec §4,
 * ADR-0013). The world is y-up; a pose's orientation rotates the z-up model frame of the face tables into it.
 */
class UpFaceTest {

    private static final Vector3dc WORLD_UP = new Vector3d(0, 1, 0);

    /** Just past the tolerance, in degrees, either way. */
    private static final double MARGIN_DEGREES = 0.01;

    /** Horizontal tilt axes, every 45° round the vertical, so a tilt toward every kind of neighbour is tried. */
    private static final List<Vector3dc> TILT_AXES = Stream.iterate(0, a -> a < 360, a -> a + 45)
            .<Vector3dc>map(a -> new Vector3d(Math.cos(Math.toRadians(a)), 0, Math.sin(Math.toRadians(a))))
            .toList();

    @Test
    void cockedToleranceIsFourDegrees() {
        assertThat(UpFace.COCKED_TOLERANCE_DEGREES).isEqualTo(4.0);
    }

    /** Every face of every Die Kind, lying flat with a few headings round the vertical. */
    @ParameterizedTest(name = "{0} face {1}")
    @MethodSource("everyFace")
    void readsEveryFaceLyingFlat(DieKind kind, int face) {
        for (double heading = 0; heading < 360; heading += 72) {
            Quaterniond orientation = flatOn(kind, face).premul(new Quaterniond().rotationY(Math.toRadians(heading)));

            UpFace up = UpFace.read(kind, orientation);

            assertThat(up.kind()).isEqualTo(kind);
            assertThat(up.face()).as("heading %s", heading).isEqualTo(face);
            assertThat(up.numeral()).isEqualTo(kind.numeral(face));
            assertThat(up.readingNormal().distance(WORLD_UP)).isLessThan(1e-9);
            assertThat(up.tiltDegrees()).isCloseTo(0, within(1e-5));
            assertThat(up.cocked()).isFalse();
        }
    }

    /** Every face of every Die Kind, tipped just past the tolerance in eight directions: still read, but cocked. */
    @ParameterizedTest(name = "{0} face {1}")
    @MethodSource("everyFace")
    void isCockedJustPastTheTolerance(DieKind kind, int face) {
        double tilt = UpFace.COCKED_TOLERANCE_DEGREES + MARGIN_DEGREES;
        for (Vector3dc axis : TILT_AXES) {
            UpFace up = UpFace.read(kind, tipped(flatOn(kind, face), axis, tilt));

            assertThat(up.face()).as("tipped about %s", axis).isEqualTo(face);
            assertThat(up.tiltDegrees()).isCloseTo(tilt, within(1e-6));
            assertThat(up.cocked()).as("tipped about %s", axis).isTrue();
        }
    }

    /** Every face of every Die Kind, tipped just inside the tolerance in eight directions: flat enough. */
    @ParameterizedTest(name = "{0} face {1}")
    @MethodSource("everyFace")
    void isNotCockedJustInsideTheTolerance(DieKind kind, int face) {
        double tilt = UpFace.COCKED_TOLERANCE_DEGREES - MARGIN_DEGREES;
        for (Vector3dc axis : TILT_AXES) {
            UpFace up = UpFace.read(kind, tipped(flatOn(kind, face), axis, tilt));

            assertThat(up.face()).as("tipped about %s", axis).isEqualTo(face);
            assertThat(up.tiltDegrees()).isCloseTo(tilt, within(1e-6));
            assertThat(up.cocked()).as("tipped about %s", axis).isFalse();
        }
    }

    /**
     * The face read is the one whose normal is nearest vertical. A d6 with its 6 (+z) up is rolled about the
     * world x axis toward its 3 (+y): at 40° the 6 is still nearest vertical, at 50° the 3 is, 40° off.
     */
    @Test
    void readsTheFaceWhoseNormalIsNearestVertical() {
        // -90° about x takes model +z to world +y and model +y to world -z; rolling a further +θ about x lifts
        // world -z toward +y. (rotationAxis, not Quaterniond.rotationX: JOML 1.10.8's double-precision rotationX
        // builds a wrong quaternion, (sin, 0, cos, 0) instead of (sin, 0, 0, cos).)
        Quaterniond sixUp = aboutX(-90);

        UpFace at40 = UpFace.read(DieKind.D6, new Quaterniond(sixUp).premul(aboutX(40)));
        UpFace at50 = UpFace.read(DieKind.D6, new Quaterniond(sixUp).premul(aboutX(50)));

        assertThat(at40.numeral()).isEqualTo("6");
        assertThat(at40.tiltDegrees()).isCloseTo(40, within(1e-9));
        assertThat(at50.numeral()).isEqualTo("3");
        assertThat(at50.tiltDegrees()).isCloseTo(40, within(1e-9));
        assertThat(at50.cocked()).isTrue();
    }

    /**
     * A d4 is read from the top: with its corner 1, (1, 1, 1) in the research's frame, turned straight up, it
     * rests on face 1, the face without that corner. The reading normal is that face's normal negated.
     */
    @Test
    void d4ReadsTheCornerUpFromTheFaceMostNearlyDown() {
        Quaterniond cornerOneUp = new Quaterniond().rotationTo(new Vector3d(1, 1, 1).normalize(), WORLD_UP);

        UpFace up = UpFace.read(DieKind.D4, cornerOneUp);

        assertThat(up.numeral()).isEqualTo("1");
        Vector3d downNormal = cornerOneUp.transform(new Vector3d(DieKind.D4.solid().faces().get(up.face()).normal()));
        assertThat(downNormal.distance(0, -1, 0)).isLessThan(1e-9);
        assertThat(up.readingNormal().distance(downNormal.negate(new Vector3d()))).isLessThan(1e-12);
        assertThat(up.cocked()).isFalse();
    }

    static Stream<Arguments> everyFace() {
        List<Arguments> out = new ArrayList<>();
        for (DieKind kind : DieKind.values()) {
            for (int face = 0; face < kind.faceCount(); face++) {
                out.add(Arguments.of(kind, face));
            }
        }
        return out.stream();
    }

    /**
     * An orientation that leaves {@code face} flat and reading: its normal straight up, or on the top-read d4
     * its normal straight down (so the corner it lacks points up).
     */
    private static Quaterniond flatOn(DieKind kind, int face) {
        Vector3d normal = new Vector3d(kind.solid().faces().get(face).normal());
        Vector3d reading = kind == DieKind.D4 ? normal.negate() : normal;
        return new Quaterniond().rotationTo(reading, WORLD_UP);
    }

    /** A rotation by {@code degrees} about the world x axis. */
    private static Quaterniond aboutX(double degrees) {
        return new Quaterniond().rotationAxis(Math.toRadians(degrees), 1, 0, 0);
    }

    /** {@code orientation} tipped by {@code degrees} about a world axis. */
    private static Quaterniondc tipped(Quaterniond orientation, Vector3dc axis, double degrees) {
        return new Quaterniond(orientation)
                .premul(new Quaterniond().rotationAxis(Math.toRadians(degrees), axis.x(), axis.y(), axis.z()));
    }
}
