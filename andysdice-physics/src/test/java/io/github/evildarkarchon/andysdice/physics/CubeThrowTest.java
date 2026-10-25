package io.github.evildarkarchon.andysdice.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.simulation.Attempt;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.BodyKind;
import io.github.evildarkarchon.andysdice.core.simulation.ContactBody;
import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import io.github.evildarkarchon.andysdice.core.simulation.ContactMaterial;
import io.github.evildarkarchon.andysdice.core.simulation.DieStart;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.FrozenDie;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Real Jolt, no GPU: the hand-made cube Thrown into a flat box (spec slice S3). These check invariants of the
 * Recording, never a trajectory (ADR-0005).
 */
class CubeThrowTest {

    /** The Footprint (ADR-0010): 300 × 200 mm inside 30 mm walls. */
    private static final TrayGeometry BOX = TrayGeometry.rectangle(15, 10, 3);

    /**
     * Green felt in walnut, from the tray prototype's numbers (Appendix C): Jolt's geometric mean of a 0.4 die
     * against felt's 0.5 and the walls' 0.5, felt's 0.15 bounce and the walls' 0.5, and felt's spin damping.
     */
    private static final TrayMaterials FELT = new TrayMaterials(
            new ContactMaterial((float) Math.sqrt(0.4 * 0.5), 0.15f),
            new ContactMaterial((float) Math.sqrt(0.4 * 0.5), 0.5f),
            new ContactMaterial(0.2f, 0.15f), 0.6f);

    /** A gentle throw from over the near end, tumbling, towards the far wall. */
    private static final DieStart THROW = new DieStart(
            new Pose(new Vector3(-2, 5.6f, -8), Orientation.about(new Vector3(1, 2, 3), 0.7f)),
            new Vector3(8, -35, 45), new Vector3(6, -9, 12));

    private static Attempt simulate(AttemptRequest request) {
        return TestWorker.get().submit(simulator -> simulator.simulate(request)).join();
    }

    private static Attempt throwTheCube() {
        return simulate(new AttemptRequest(List.of(TestCube.INSTANCE), new Release.Given(List.of(THROW)), List.of(),
                BOX, FELT));
    }

    @Test
    void theCubeComesToRestFlatOnTheFloor() {
        Attempt attempt = throwTheCube();

        assertThat(attempt.ending()).isEqualTo(Ending.AT_REST);
        assertThat(attempt.cockedDice()).isEmpty();
        Pose last = attempt.recording().finalPose(0);
        assertThat(last.position().y()).isCloseTo(TestCube.INRADIUS, within(0.05f));
        assertThat(TestCube.tiltDegrees(last.orientation())).isLessThan(1);
        assertThat(BOX.depthInside(last.position().x(), last.position().z())).isGreaterThan(TestCube.INRADIUS * 0.99f);
    }

    @Test
    void itsRecordingStartsAtTheReleaseAndEndsAtRestWithinTheTimeCap() {
        Recording recording = throwTheCube().recording();

        assertThat(recording.dice()).isEqualTo(1);
        Pose first = recording.pose(0, 0);
        assertThat(first.position().x()).isCloseTo(THROW.pose().position().x(), within(1e-4f));
        assertThat(first.position().y()).isCloseTo(THROW.pose().position().y(), within(1e-4f));
        assertThat(first.position().z()).isCloseTo(THROW.pose().position().z(), within(1e-4f));
        assertThat(recording.restTime()).isGreaterThan(0).isLessThanOrEqualTo(Simulator.TIME_CAP_SECONDS);
        // The Recording's own checks hold the last sample to within one sample after the rest.
        assertThat(recording.duration()).isGreaterThanOrEqualTo(recording.restTime());
    }

    @Test
    void itsPosesAreWellFormedAndContinuous() {
        Recording recording = throwTheCube().recording();

        // The throw is under 60 cm/s and gravity adds about 15 cm/s over the 6 cm drop, so no sample can be
        // more than a centimetre from the one before it at 120 Hz.
        for (int s = 0; s < recording.samples(); s++) {
            Pose pose = recording.pose(0, s);
            assertThat(pose.position().isFinite()).isTrue();
            assertThat(pose.orientation().norm()).isCloseTo(1, within(1e-3f));
            assertThat(pose.position().y()).isGreaterThan(TestCube.INRADIUS * 0.9f);
            if (s > 0) {
                Vector3 a = recording.pose(0, s - 1).position();
                Vector3 b = pose.position();
                assertThat(new Vector3(b.x() - a.x(), b.y() - a.y(), b.z() - a.z()).length()).isLessThan(1);
            }
        }
    }

    @Test
    void itsContactsAreTimedWithinTheThrowAndStrikeTheFloor() {
        Recording recording = throwTheCube().recording();
        List<ContactEvent> contacts = recording.contacts();

        assertThat(contacts).isNotEmpty();
        ContactEvent landing = contacts.getFirst();
        assertThat(landing.other().kind()).isEqualTo(BodyKind.FLOOR);
        assertThat(landing.point().y()).isCloseTo(0, within(0.2f));
        for (ContactEvent contact : contacts) {
            assertThat(contact.die().index()).isZero();
            assertThat(contact.time()).isGreaterThan(0).isLessThanOrEqualTo(recording.restTime());
            assertThat(contact.impulse()).isPositive();
            assertThat(BOX.depthInside(contact.point().x(), contact.point().z())).isGreaterThan(-0.1f);
        }
    }

    @Test
    void aDrawnReleaseThrowsTheCubeIntoTheTrayToRest() {
        Attempt attempt = simulate(new AttemptRequest(List.of(TestCube.INSTANCE),
                new Release.Drawn(new SplittableRandom(20261025)), List.of(), BOX, FELT));

        assertThat(attempt.ending()).isEqualTo(Ending.AT_REST);
        Pose first = attempt.recording().pose(0, 0);
        assertThat(first.position().z()).isNegative(); // let go over the near end
        assertThat(BOX.depthInside(first.position().x(), first.position().z())).isPositive();
    }

    @Test
    void aCubeLeftOutsideTheWallsHasEscaped() {
        DieStart outside = new DieStart(new Pose(new Vector3(20, 2, 0), Orientation.IDENTITY), Vector3.ZERO,
                Vector3.ZERO);
        Attempt attempt = simulate(new AttemptRequest(List.of(TestCube.INSTANCE),
                new Release.Given(List.of(outside)), List.of(), BOX, FELT));

        assertThat(attempt.ending()).isEqualTo(Ending.ESCAPED);
        assertThat(attempt.recording().restTime()).isLessThan(Simulator.TIME_CAP_SECONDS);
    }

    @Test
    void aCubeThatNeverStopsTimesOutAtTheCap() {
        // No friction or spin damping, and walls that return every bit of speed: it slides wall to wall forever.
        TrayMaterials ice = new TrayMaterials(new ContactMaterial(0, 0), new ContactMaterial(0, 1),
                new ContactMaterial(0, 0), 0);
        DieStart sliding = new DieStart(new Pose(new Vector3(0, TestCube.INRADIUS, 0), Orientation.IDENTITY),
                new Vector3(100, 0, 0), Vector3.ZERO);
        Attempt attempt = simulate(new AttemptRequest(List.of(TestCube.INSTANCE),
                new Release.Given(List.of(sliding)), List.of(), BOX, ice));

        assertThat(attempt.ending()).isEqualTo(Ending.TIMED_OUT);
        assertThat(attempt.recording().restTime()).isEqualTo(Simulator.TIME_CAP_SECONDS);
        assertThat(attempt.recording().duration()).isCloseTo(Simulator.TIME_CAP_SECONDS, within(1e-4f));
    }

    @Test
    void aCubeRestingOnATiltedFrozenDieIsCocked() {
        // A Frozen Die tipped 8° about z; friction 0.2 holds a cube on that slope (tan 8° is about 0.14).
        Orientation tipped = Orientation.about(new Vector3(0, 0, 1), (float) Math.toRadians(8));
        FrozenDie propped = new FrozenDie(TestCube.INSTANCE, new Pose(new Vector3(0, TestCube.INRADIUS, 0), tipped));
        Vector3 up = tipped.rotate(Vector3.UP);
        float lift = 2 * TestCube.INRADIUS + 0.01f;
        DieStart onTop = new DieStart(new Pose(
                new Vector3(up.x() * lift, TestCube.INRADIUS + up.y() * lift, 0), tipped), Vector3.ZERO, Vector3.ZERO);
        Attempt attempt = simulate(new AttemptRequest(List.of(TestCube.INSTANCE),
                new Release.Given(List.of(onTop)), List.of(propped), BOX, FELT));

        assertThat(attempt.ending()).isEqualTo(Ending.COCKED);
        assertThat(attempt.cockedDice()).containsExactly(0);
        assertThat(attempt.recording().contacts()).isNotEmpty()
                .allSatisfy(c -> assertThat(c.other()).isEqualTo(
                        ContactBody.frozenDie(0)));
    }
}
