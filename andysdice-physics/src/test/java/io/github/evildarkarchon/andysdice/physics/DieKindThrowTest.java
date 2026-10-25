package io.github.evildarkarchon.andysdice.physics;

import static io.github.evildarkarchon.andysdice.physics.Scenarios.BOX;
import static io.github.evildarkarchon.andysdice.physics.Scenarios.CAP;
import static io.github.evildarkarchon.andysdice.physics.Scenarios.FELT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.Attempt;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.DieBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieKindBody;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real Jolt Throws of every Die Kind, alone and in the 20-Dice cap mix. These check invariants, never a
 * trajectory (ADR-0005).
 */
class DieKindThrowTest {

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void eachKindSetDownOnAFaceRestsFlatAtItsInradius(DieKind kind) {
        DieBody die = DieKindBody.of(kind);
        float inradius = (float) kind.inradius() / 10;
        Attempt attempt = Scenarios.simulate(Scenarios.given(List.of(die),
                List.of(Scenarios.still(new Vector3(1, inradius + 0.05f, 2), Scenarios.flat(kind, 0))), List.of()));

        assertThat(attempt.ending()).isEqualTo(Ending.AT_REST);
        Pose last = attempt.recording().finalPose(0);
        // The rounded hull keeps the solid's face planes, so a Die lying on a face rests at its inradius.
        assertThat(last.position().y()).isCloseTo(inradius, within(0.01f));
        assertThat(die.isCocked(last.orientation())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4})
    void theTwentyDiceCapThrownFromTheReleaseEndsInOneOfTheFourEndings(long seed) {
        Attempt attempt = Scenarios.simulate(new AttemptRequest(CAP, new Release.Drawn(new SplittableRandom(seed)),
                List.of(), BOX, FELT));
        Recording recording = attempt.recording();

        assertThat(recording.dice()).isEqualTo(CAP.size());
        assertThat(recording.restTime()).isPositive().isLessThanOrEqualTo(Simulator.TIME_CAP_SECONDS);
        for (int die = 0; die < CAP.size(); die++) {
            for (int s = 0; s < recording.samples(); s++) {
                Pose pose = recording.pose(die, s);
                assertThat(pose.position().isFinite()).isTrue();
                assertThat(pose.orientation().norm()).isCloseTo(1, within(1e-3f));
            }
            assertThat(Scenarios.largestStepPerSample(recording, die)).isLessThan(1.5);
        }
        if (attempt.ending() == Ending.AT_REST || attempt.ending() == Ending.COCKED) {
            for (int die = 0; die < CAP.size(); die++) {
                Pose last = recording.finalPose(die);
                assertThat(BOX.depthInside(last.position().x(), last.position().z())).isPositive();
                assertThat(CAP.get(die).isCocked(last.orientation()))
                        .isEqualTo(attempt.cockedDice().contains(die));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {5, 6})
    void theReleaseLaysTheCapOutInOneLayerOverTheNearEnd(long seed) {
        Recording recording = Scenarios.simulate(new AttemptRequest(CAP,
                new Release.Drawn(new SplittableRandom(seed)), List.of(), BOX, FELT)).recording();

        float height = recording.pose(0, 0).position().y();
        for (int die = 0; die < CAP.size(); die++) {
            Vector3 start = recording.pose(die, 0).position();
            assertThat(start.y()).as("Die %s starts in the one layer", die).isEqualTo(height);
            assertThat(start.y()).isGreaterThan(BOX.wallHeight());
            assertThat(start.z()).isNegative();
            assertThat(BOX.depthInside(start.x(), start.z())).isGreaterThan(1.3f);
        }
    }
}
