package io.github.evildarkarchon.andysdice.core.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Playback draws a Recording between its 120 Hz samples (ADR-0014): positions linearly, orientations by slerp,
 * so a Die tumbling up to 90° between samples still turns at an even rate.
 */
class InterpolationTest {

    private static final double SAMPLE = 1.0 / Recording.SAMPLE_RATE_HZ;
    private static final Vector3 Z = new Vector3(0, 0, 1);
    private static final Orientation QUARTER_TURN = Orientation.about(Z, (float) (Math.PI / 2));

    /** One Die over three samples: it slides 1.2 cm along x while turning 90° about z, then rises 2.4 cm. */
    private static final Recording SLIDE = recording(
            pose(0, 0, 0, Orientation.IDENTITY),
            pose(1.2f, 0, 0, QUARTER_TURN),
            pose(1.2f, 2.4f, 0, QUARTER_TURN));

    @Test
    void atASampleTimeItIsThatSample() {
        assertPose(Interpolation.pose(SLIDE, 0, 0), SLIDE.pose(0, 0));
        assertPose(Interpolation.pose(SLIDE, 0, SAMPLE), SLIDE.pose(0, 1));
        assertPose(Interpolation.pose(SLIDE, 0, 2 * SAMPLE), SLIDE.pose(0, 2));
    }

    @Test
    void betweenSamplesThePositionMovesInAStraightLine() {
        Vector3 quarter = Interpolation.pose(SLIDE, 0, SAMPLE / 4).position();
        Vector3 later = Interpolation.pose(SLIDE, 0, SAMPLE * 1.5).position();

        assertThat(quarter.x()).isCloseTo(0.3f, within(1e-5f));
        assertThat(quarter.y()).isCloseTo(0, within(1e-5f));
        assertThat(later.x()).isCloseTo(1.2f, within(1e-5f));
        assertThat(later.y()).isCloseTo(1.2f, within(1e-5f));
    }

    @Test
    void betweenSamplesTheOrientationTurnsAtAnEvenRate() {
        // A quarter of the way through a 90° turn is 22.5°; a normalised lerp would give about 20.1°.
        Orientation quarter = Interpolation.pose(SLIDE, 0, SAMPLE / 4).orientation();
        Orientation half = Interpolation.pose(SLIDE, 0, SAMPLE / 2).orientation();

        assertThat(turnAboutZDegrees(quarter)).isCloseTo(22.5, within(1e-3));
        assertThat(turnAboutZDegrees(half)).isCloseTo(45, within(1e-3));
        assertThat(quarter.norm()).isCloseTo(1, within(1e-6f));
    }

    @Test
    void itTurnsTheShortWayWhenTheNextSampleHasTheOppositeSign() {
        // -q is the same rotation as q, as a simulator may report it; the short way from identity is still 90°.
        Orientation flipped = new Orientation(-QUARTER_TURN.x(), -QUARTER_TURN.y(), -QUARTER_TURN.z(),
                -QUARTER_TURN.w());
        Recording recording = recording(pose(0, 0, 0, Orientation.IDENTITY), pose(0, 0, 0, flipped));

        Orientation half = Interpolation.pose(recording, 0, SAMPLE / 2).orientation();

        assertThat(turnAboutZDegrees(half)).isCloseTo(45, within(1e-3));
    }

    @Test
    void beforeTheReleaseItIsTheReleaseAndAfterTheLastSampleItStaysWhereItStopped() {
        assertPose(Interpolation.pose(SLIDE, 0, -1), SLIDE.pose(0, 0));
        assertPose(Interpolation.pose(SLIDE, 0, 10), SLIDE.finalPose(0));
    }

    @Test
    void eachDieIsDrawnFromItsOwnSamples() {
        float[] poses = {
            // sample 0: Die 0 at the origin, Die 1 at x = 10
            0, 0, 0, 0, 0, 0, 1, 10, 0, 0, 0, 0, 0, 1,
            // sample 1: Die 0 at z = 2, Die 1 at x = 12
            0, 0, 2, 0, 0, 0, 1, 12, 0, 0, 0, 0, 0, 1,
        };
        Recording two = new Recording(2, poses, (float) SAMPLE, List.of());

        assertThat(Interpolation.pose(two, 0, SAMPLE / 2).position().z()).isCloseTo(1, within(1e-5f));
        assertThat(Interpolation.pose(two, 1, SAMPLE / 2).position().x()).isCloseTo(11, within(1e-5f));
    }

    /** The angle, in degrees, by which an orientation turns the x axis about z. */
    private static double turnAboutZDegrees(Orientation orientation) {
        Vector3 x = orientation.rotate(new Vector3(1, 0, 0));
        assertThat(x.z()).isCloseTo(0, within(1e-5f));
        return Math.toDegrees(Math.atan2(x.y(), x.x()));
    }

    private static void assertPose(Pose actual, Pose expected) {
        assertThat(actual.position().x()).isCloseTo(expected.position().x(), within(1e-5f));
        assertThat(actual.position().y()).isCloseTo(expected.position().y(), within(1e-5f));
        assertThat(actual.position().z()).isCloseTo(expected.position().z(), within(1e-5f));
        Vector3 x = new Vector3(1, 0, 0);
        Vector3 y = new Vector3(0, 1, 0);
        for (Vector3 axis : List.of(x, y)) {
            Vector3 a = actual.orientation().rotate(axis);
            Vector3 e = expected.orientation().rotate(axis);
            assertThat(a.x()).isCloseTo(e.x(), within(1e-5f));
            assertThat(a.y()).isCloseTo(e.y(), within(1e-5f));
            assertThat(a.z()).isCloseTo(e.z(), within(1e-5f));
        }
    }

    private static Pose pose(float x, float y, float z, Orientation orientation) {
        return new Pose(new Vector3(x, y, z), orientation);
    }

    /** A one-Die Recording of the given samples, at rest at its last sample. */
    private static Recording recording(Pose... samples) {
        float[] poses = new float[samples.length * 7];
        for (int i = 0; i < samples.length; i++) {
            Pose p = samples[i];
            float[] floats = {p.position().x(), p.position().y(), p.position().z(), p.orientation().x(),
                p.orientation().y(), p.orientation().z(), p.orientation().w()};
            System.arraycopy(floats, 0, poses, i * 7, 7);
        }
        return new Recording(1, poses, (samples.length - 1) / (float) Recording.SAMPLE_RATE_HZ, List.of());
    }
}
