package io.github.evildarkarchon.andysdice.physics;

import static io.github.evildarkarchon.andysdice.physics.Scenarios.BOX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.Attempt;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.ContactMaterial;
import io.github.evildarkarchon.andysdice.core.simulation.DieBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieKindBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieStart;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.FrozenDie;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Scenario tests that force each of the four endings (ADR-0003) with Die Kinds' bodies. */
class EndingsTest {

    private static final DieBody D6 = DieKindBody.of(DieKind.D6);
    private static final float D6_INRADIUS = 0.8f;

    @Test
    void aD20TossedIntoTheTrayComesToRest() {
        DieStart toss = new DieStart(new Pose(new Vector3(-3, 5, -6), Orientation.about(new Vector3(1, 2, 3), 1.1f)),
                new Vector3(10, -30, 40), new Vector3(5, -8, 9));
        Attempt attempt = Scenarios.simulate(Scenarios.given(List.of(DieKindBody.of(DieKind.D20)), List.of(toss),
                List.of()));

        assertThat(attempt.ending()).isEqualTo(Ending.AT_REST);
        assertThat(attempt.cockedDice()).isEmpty();
        assertThat(attempt.recording().restTime()).isLessThan(Simulator.TIME_CAP_SECONDS);
    }

    @Test
    void aD6ProppedOnATiltedFrozenD6StaysCockedThroughEveryNudge() {
        // A Frozen d6 tipped 8° about z; friction 0.2 holds a d6 on that slope (tan 8° is about 0.14), and no
        // Settle Nudge can level a Die whose support is itself tilted.
        Orientation tipped = Orientation.about(new Vector3(0, 0, 1), (float) Math.toRadians(8))
                .times(Scenarios.flat(DieKind.D6, 5));
        FrozenDie propped = new FrozenDie(D6, new Pose(new Vector3(0, D6_INRADIUS, 0), tipped));
        Vector3 up = tipped.rotate(new Vector3(0, 0, 1));
        float lift = 2 * D6_INRADIUS + 0.01f;
        AttemptRequest request = Scenarios.given(List.of(D6), List.of(Scenarios.still(
                new Vector3(up.x() * lift, D6_INRADIUS + up.y() * lift, up.z() * lift), tipped)), List.of(propped));

        JoltSimulator.Run run = Scenarios.run(request);

        assertThat(run.attempt().ending()).isEqualTo(Ending.COCKED);
        assertThat(run.attempt().cockedDice()).containsExactly(0);
        assertThat(run.nudges()).containsExactly(JoltSimulator.SETTLE_NUDGES);
    }

    @Test
    void aD8LeftOutsideTheWallsHasEscaped() {
        Attempt attempt = Scenarios.simulate(Scenarios.given(List.of(DieKindBody.of(DieKind.D8)),
                List.of(Scenarios.still(new Vector3(20, 2, 0), Scenarios.flat(DieKind.D8, 0))), List.of()));

        assertThat(attempt.ending()).isEqualTo(Ending.ESCAPED);
        assertThat(attempt.recording().restTime()).isLessThan(Simulator.TIME_CAP_SECONDS);
    }

    @Test
    void aD6ThatNeverStopsTimesOutAtTheCap() {
        // No friction or spin damping, and walls that return every bit of speed: it slides wall to wall forever.
        TrayMaterials ice = new TrayMaterials(new ContactMaterial(0, 0), new ContactMaterial(0, 1),
                new ContactMaterial(0, 0), 0);
        DieStart sliding = new DieStart(new Pose(new Vector3(0, D6_INRADIUS, 0), Scenarios.flat(DieKind.D6, 5)),
                new Vector3(100, 0, 0), Vector3.ZERO);
        Attempt attempt = Scenarios.simulate(new AttemptRequest(List.of(D6), new Release.Given(List.of(sliding)),
                List.of(), BOX, ice));

        assertThat(attempt.ending()).isEqualTo(Ending.TIMED_OUT);
        assertThat(attempt.recording().restTime()).isEqualTo(Simulator.TIME_CAP_SECONDS);
        assertThat(attempt.recording().duration()).isCloseTo(Simulator.TIME_CAP_SECONDS, within(1e-4f));
    }
}
