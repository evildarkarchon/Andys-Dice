package io.github.evildarkarchon.andysdice.physics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.DieBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieKindBody;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Settle Nudges (ADR-0013): a Die settling cocked is tipped onto its nearest face within the Attempt, so it ends
 * flat and its Recording stays one continuous motion.
 *
 * <p>The scenario is a d6 set down balanced on one edge, 45° from either face: it has nothing to fall either way,
 * so without nudges it settles there, cocked.
 */
class SettleNudgeTest {

    private static final DieBody D6 = DieKindBody.of(DieKind.D6);

    /** On its rounded edge the d6's centre is r + (inradius - r)·√2 above the floor, in cm. */
    private static final float ON_EDGE = (float) (0.075 + (0.8 - 0.075) * Math.sqrt(2));

    private static final AttemptRequest ON_ITS_EDGE = Scenarios.given(List.of(D6), List.of(Scenarios.still(
            new Vector3(2, ON_EDGE + 0.002f, 1),
            Orientation.about(new Vector3(0, 0, 1), (float) Math.toRadians(45)).times(Scenarios.flat(DieKind.D6, 5)))),
            List.of());

    private static JoltSimulator.Run nudged;
    private static JoltSimulator.Run unnudged;

    @BeforeAll
    static void throwBoth() {
        nudged = Scenarios.run(ON_ITS_EDGE);
        unnudged = Scenarios.runWithoutNudges(ON_ITS_EDGE);
    }

    @Test
    void withoutNudgesTheBalancedD6RestsCocked() {
        assertThat(unnudged.attempt().ending()).isEqualTo(Ending.COCKED);
        assertThat(unnudged.nudges()).containsExactly(0);
    }

    @Test
    void aNudgedDieEndsFlat() {
        assertThat(nudged.nudges().getFirst()).isBetween(1, JoltSimulator.SETTLE_NUDGES);
        assertThat(nudged.attempt().ending()).isEqualTo(Ending.AT_REST);
        Recording recording = nudged.attempt().recording();
        assertThat(D6.isCocked(recording.finalPose(0).orientation())).isFalse();
        assertThat(recording.finalPose(0).position().y()).isLessThan(0.81f);
    }

    @Test
    void aNudgedDiesRecordingHasNoDiscontinuity() {
        Recording recording = nudged.attempt().recording();

        // Tipping from the edge onto a face drops the centre 3 mm (1.10 to 0.80 cm), which gives at most
        // √(2 · 981 · 0.3) ≈ 24 cm/s, plus the nudge's 4 cm/s hop: under 2.5 mm per sample at 120 Hz. Turning
        // 45° about the edge at that speed is under 22 rad/s, about 10° per sample. A jump (a Die moved rather
        // than nudged) would break these.
        assertThat(Scenarios.largestStepPerSample(recording, 0)).isLessThan(0.25);
        assertThat(Scenarios.largestTurnPerSample(recording, 0)).isLessThan(Math.toRadians(12));
    }

    @Test
    void theNudgedDiesFallOntoItsFaceIsHeardLikeAnyOtherContact() {
        // ADR-0013: the nudge's motion makes sound like any other. The unnudged Die, which never tips, makes none
        // after it was set down.
        assertThat(nudged.attempt().recording().contacts())
                .anySatisfy(c -> assertThat(c.time()).isGreaterThan(JoltSimulator.NUDGE_STILL_SECONDS));
        assertThat(unnudged.attempt().recording().contacts())
                .allSatisfy(c -> assertThat(c.time()).isLessThan(JoltSimulator.NUDGE_STILL_SECONDS));
    }

    @Test
    void theDieWasStillOnItsEdgeForAFifthOfASecondBeforeItsFirstNudge() {
        // Nudges wait for 0.15 s of stillness, so the Die is not tipped while it is still settling.
        Recording recording = nudged.attempt().recording();
        int samples = Math.round(JoltSimulator.NUDGE_STILL_SECONDS * Recording.SAMPLE_RATE_HZ);
        for (int s = 0; s < samples; s++) {
            assertThat(recording.pose(0, s).position().y()).isGreaterThan(ON_EDGE - 0.01f);
        }
    }
}
