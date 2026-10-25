package io.github.evildarkarchon.andysdice.render.camera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import java.util.List;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The settle-in camera on a playback clock advanced by hand (ADR-0011: no test sleeps): it frames the whole Tray
 * while Dice tumble, eases in on the Dice at rest and back out before the next Throw, and lays the Player's
 * orbit and zoom over that, within the limits, until the next Throw starts. With reduced motion every ease is a
 * cut. No GL.
 */
class TrayCameraTest {

    private static final double ASPECT = 1.6;
    private static final Framing FOOTPRINT = Framing.of(TrayGeometry.rectangle(15, 10, 3));
    private static final List<DiceScene.Die> ONE_DIE =
            List.of(new DiceScene.Die(DieKind.D6, new Vector3d(30, 8, -20), new Quaterniond()));

    private PlaybackClock clock;
    private boolean reducedMotion;
    private TrayCamera camera;

    @BeforeEach
    void setUp() {
        clock = new PlaybackClock();
        // Playback doesn't start at time 0 in the app either: scripts run from when they're played.
        clock.advance(3);
        camera = new TrayCamera(clock, FOOTPRINT, () -> reducedMotion);
        camera.setAspect(ASPECT);
    }

    private CameraPose tray() {
        return FOOTPRINT.wholeTray(ASPECT);
    }

    private CameraPose closeUp() {
        return FOOTPRINT.resting(ONE_DIE, ASPECT);
    }

    // The scripts' times are binary fractions, so the clock lands on each cue exactly.

    /** One Throw: out to the whole Tray as it starts, at rest at 1 s, eased in by 2 s. */
    private static CameraScript oneThrow() {
        return new CameraScript(List.of(
                new CameraScript.Cue(0, new CameraScript.Shot.WholeTray(), 0.5),
                new CameraScript.Cue(1, new CameraScript.Shot.Resting(ONE_DIE), 1)), List.of(0.0));
    }

    private static void assertSamePose(CameraPose actual, CameraPose expected) {
        assertThat(actual.yawDegrees()).isCloseTo(expected.yawDegrees(), within(1e-9));
        assertThat(actual.pitchDegrees()).isCloseTo(expected.pitchDegrees(), within(1e-9));
        assertThat(actual.distance()).isCloseTo(expected.distance(), within(1e-9));
        assertThat(actual.target().distance(expected.target())).isLessThan(1e-9);
    }

    @Test
    void beforeAnyRollTheCameraFramesTheWholeTrayAndIsStill() {
        assertSamePose(camera.pose(), tray());
        assertThat(camera.moving()).isFalse();
        assertThat(camera.camera()).isEqualTo(tray().camera());
    }

    @Test
    void anotherTraysFramingTakesOverAtOnce() {
        // A Player Tray with 10 cm walls over the same footprint (#57).
        Framing tall = Framing.of(TrayGeometry.rectangle(15, 10, 10));

        camera.setFraming(tall);

        assertSamePose(camera.pose(), tall.wholeTray(ASPECT));
        assertThat(camera.pose().distance()).isGreaterThan(tray().distance());
        assertThat(camera.moving()).isFalse();
    }

    @Test
    void theCameraFramesTheTrayWhileTheDiceTumbleThenEasesInOnThemAtRest() {
        camera.play(oneThrow());

        clock.advance(0.9375);
        assertSamePose(camera.pose(), tray());
        assertThat(camera.moving()).isTrue();

        clock.advance(0.0625 + 0.25);
        // A quarter of the way through the ease it has moved less than a quarter of the way: it eases in.
        assertThat(camera.pose().pitchDegrees()).isBetween(50.0, 53.0);

        clock.advance(0.25);
        // Halfway through, halfway there.
        assertThat(camera.pose().pitchDegrees()).isCloseTo(56, within(1e-9));
        assertThat(camera.pose().distance()).isBetween(closeUp().distance(), tray().distance());

        clock.advance(0.5);
        assertSamePose(camera.pose(), closeUp());
        assertThat(camera.moving()).isFalse();
    }

    @Test
    void beforeARerollThrowTheCameraEasesBackOutToTheWholeTray() {
        camera.play(new CameraScript(List.of(
                new CameraScript.Cue(0, new CameraScript.Shot.WholeTray(), 0.5),
                new CameraScript.Cue(1, new CameraScript.Shot.Resting(ONE_DIE), 1),
                new CameraScript.Cue(2, new CameraScript.Shot.WholeTray(), 0.5),
                new CameraScript.Cue(3, new CameraScript.Shot.Resting(ONE_DIE), 1)), List.of(0.0, 2.5)));

        clock.advance(2);
        assertSamePose(camera.pose(), closeUp());
        clock.advance(0.25);
        assertThat(camera.pose().pitchDegrees()).isCloseTo(56, within(1e-9));
        clock.advance(0.25);
        assertSamePose(camera.pose(), tray());
        assertThat(camera.moving()).isTrue();
        clock.advance(1.5);
        assertSamePose(camera.pose(), closeUp());
        assertThat(camera.moving()).isFalse();
    }

    @Test
    void theNextRollEasesOutFromWhereverTheCameraIs() {
        camera.play(oneThrow());
        clock.advance(1.5);
        CameraPose midway = camera.pose();

        camera.play(oneThrow());

        // No jump: the new Roll's first ease starts from the pose the camera had.
        assertSamePose(camera.pose(), midway);
        clock.advance(0.5);
        assertSamePose(camera.pose(), tray());
    }

    @Test
    void withReducedMotionTheCameraCutsInsteadOfEasing() {
        reducedMotion = true;
        camera.play(oneThrow());
        clock.advance(0.9375);
        assertSamePose(camera.pose(), tray());

        clock.advance(0.0625);

        assertSamePose(camera.pose(), closeUp());
        assertThat(camera.moving()).isFalse();
    }

    @Test
    void anOrbitSwingsAndTiltsTheCameraAroundWhatItLooksAt() {
        camera.orbit(30, 10);

        CameraPose pose = camera.pose();
        assertThat(pose.yawDegrees()).isCloseTo(30, within(1e-9));
        assertThat(pose.pitchDegrees()).isCloseTo(60, within(1e-9));
        assertThat(pose.distance()).isCloseTo(tray().distance(), within(1e-9));
        assertThat(pose.target()).isEqualTo(tray().target());
        assertThat(camera.adjusted()).isTrue();
    }

    @Test
    void yawIsFreeAllTheWayRound() {
        camera.orbit(400, 0);
        CameraPose round = camera.pose();
        camera.reset();
        camera.orbit(40, 0);

        assertThat(round.eye().distance(camera.pose().eye())).isLessThan(1e-9);
    }

    @Test
    void pitchStopsJustShortOfStraightDownAndAt20DegreesAboveTheFloor() {
        camera.orbit(0, 100);
        assertThat(camera.pose().pitchDegrees()).isEqualTo(TrayCamera.MAX_PITCH_DEGREES);
        // Dragging on past a limit doesn't wind up: the first move back moves the camera.
        camera.orbit(0, -10);
        assertThat(camera.pose().pitchDegrees()).isCloseTo(TrayCamera.MAX_PITCH_DEGREES - 10, within(1e-9));

        camera.orbit(0, -200);
        assertThat(camera.pose().pitchDegrees()).isEqualTo(20);
        assertThat(TrayCamera.MAX_PITCH_DEGREES).isBetween(89.0, 90.0);
    }

    @Test
    void zoomRunsFrom0Point7To3TimesTheAutomaticFraming() {
        camera.zoom(0.8);
        assertThat(camera.pose().distance()).isCloseTo(tray().distance() * 0.8, within(1e-9));

        camera.zoom(0.1);
        assertThat(camera.pose().distance()).isCloseTo(tray().distance() * 0.7, within(1e-9));

        camera.zoom(100);
        assertThat(camera.pose().distance()).isCloseTo(tray().distance() * 3, within(1e-9));
    }

    @Test
    void theOrbitAndZoomRideOnTheAutomaticCameraAsItEases() {
        camera.orbit(30, 0);
        camera.zoom(2);
        camera.play(new CameraScript(oneThrow().cues(), List.of()));

        clock.advance(2);

        CameraPose pose = camera.pose();
        assertThat(pose.yawDegrees()).isCloseTo(30, within(1e-9));
        assertThat(pose.pitchDegrees()).isCloseTo(closeUp().pitchDegrees(), within(1e-9));
        assertThat(pose.distance()).isCloseTo(closeUp().distance() * 2, within(1e-9));
        assertThat(pose.target()).isEqualTo(closeUp().target());
    }

    @Test
    void whenTheNextThrowStartsAnyOrbitOrZoomEasesBackOverAbout0Point4Seconds() {
        camera.orbit(30, 10);
        camera.zoom(2);

        camera.play(oneThrow());
        assertThat(camera.pose().yawDegrees()).isCloseTo(30, within(1e-9));
        assertThat(camera.moving()).isTrue();

        clock.advance(TrayCamera.RETURN_SECONDS / 2);
        assertThat(camera.pose().yawDegrees()).isCloseTo(15, within(1e-9));
        assertThat(camera.pose().pitchDegrees()).isCloseTo(55, within(1e-9));
        assertThat(camera.pose().distance()).isCloseTo(tray().distance() * Math.sqrt(2), within(1e-6));

        clock.advance(TrayCamera.RETURN_SECONDS);
        assertSamePose(camera.pose(), tray());
        assertThat(camera.adjusted()).isFalse();
        assertThat(TrayCamera.RETURN_SECONDS).isCloseTo(0.4, within(0.1));
    }

    @Test
    void anOrbitDuringARollIsKeptUntilTheNextThrowStarts() {
        camera.play(new CameraScript(oneThrow().cues(), List.of(0.0, 2.5)));
        clock.advance(1);
        camera.orbit(-40, 0);

        clock.advance(1.25);
        assertThat(camera.pose().yawDegrees()).isCloseTo(-40, within(1e-9));

        clock.advance(0.25 + TrayCamera.RETURN_SECONDS);
        assertThat(camera.pose().yawDegrees()).isCloseTo(0, within(1e-9));
    }

    @Test
    void anOrbitWhileEasingBackCarriesOnFromWhereTheCameraIs() {
        camera.orbit(40, 0);
        camera.play(oneThrow());
        clock.advance(TrayCamera.RETURN_SECONDS / 2);

        camera.orbit(5, 0);

        assertThat(camera.pose().yawDegrees()).isCloseTo(25, within(1e-9));
        clock.advance(TrayCamera.RETURN_SECONDS);
        // The new orbit waits for the next Throw like any other.
        assertThat(camera.pose().yawDegrees()).isCloseTo(25, within(1e-9));
    }

    @Test
    void aResetDropsTheOrbitAndZoomAtOnce() {
        camera.orbit(30, 10);
        camera.zoom(2);

        camera.reset();

        assertSamePose(camera.pose(), tray());
        assertThat(camera.adjusted()).isFalse();
        assertThat(camera.moving()).isFalse();
    }

    @Test
    void withReducedMotionTheOrbitCutsBackWhenTheNextThrowStarts() {
        reducedMotion = true;
        camera.orbit(30, 10);

        camera.play(oneThrow());

        assertSamePose(camera.pose(), tray());
        assertThat(camera.moving()).isTrue();
        clock.advance(1);
        assertThat(camera.moving()).isFalse();
    }

    @Test
    void aWiderOrNarrowerViewIsReframed() {
        camera.setAspect(1.0);

        assertSamePose(camera.pose(), FOOTPRINT.wholeTray(1.0));
    }
}
