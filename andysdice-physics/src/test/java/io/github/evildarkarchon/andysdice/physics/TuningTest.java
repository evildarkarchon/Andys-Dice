package io.github.evildarkarchon.andysdice.physics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.BodyKind;
import io.github.evildarkarchon.andysdice.core.simulation.DieBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieKindBody;
import io.github.evildarkarchon.andysdice.core.simulation.FrozenDie;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Jolt simulator's tuning against spec §4: a 240 Hz step, a 4 s cap and a 120 Hz Recording (ADR-0014),
 * three Settle Nudges and die-on-die friction of 0.2 (ADR-0013).
 */
class TuningTest {

    private static final DieBody D6 = DieKindBody.of(DieKind.D6);

    @Test
    void itStepsAt240HzForAtMostFourSecondsAndRecordsAt120Hz() {
        assertThat(JoltSimulator.STEP_HZ).isEqualTo(240);
        assertThat(Simulator.TIME_CAP_SECONDS).isEqualTo(4);
        assertThat(Recording.SAMPLE_RATE_HZ).isEqualTo(120);
    }

    @Test
    void eachDieGetsThreeSettleNudges() {
        assertThat(JoltSimulator.SETTLE_NUDGES).isEqualTo(3);
    }

    @Test
    void dieOnDieContactsUseTheDieOnDieMaterialAgainstThrownAndFrozenDice() {
        TrayMaterials felt = Scenarios.FELT;

        assertThat(ContactRecorder.material(felt, BodyKind.DIE, BodyKind.DIE).friction()).isEqualTo(0.2f);
        assertThat(ContactRecorder.material(felt, BodyKind.FROZEN_DIE, BodyKind.DIE)).isEqualTo(felt.dieOnDie());
        assertThat(ContactRecorder.material(felt, BodyKind.DIE, BodyKind.FLOOR)).isEqualTo(felt.floor());
        assertThat(ContactRecorder.material(felt, BodyKind.WALL, BodyKind.DIE)).isEqualTo(felt.walls());
    }

    @Test
    void aDieHoldsOnADieTipped10DegreesButSlidesOffOneTipped14() {
        // With friction 0.2 a Die holds on a slope up to atan 0.2 ≈ 11.3°. Against felt's 0.45 it would hold
        // to 24°, so a slide at 14° shows Jolt uses the die-on-die value.
        assertThat(heightAfterRestingOn(10)).isGreaterThan(2.3f);
        assertThat(heightAfterRestingOn(14)).isLessThan(1.6f);
    }

    /** Sets a d6 on a Frozen d6 tipped by {@code degrees} and returns its centre's final height, in cm. */
    private static float heightAfterRestingOn(double degrees) {
        float inradius = 0.8f;
        Orientation tipped = Orientation.about(new Vector3(0, 0, 1), (float) Math.toRadians(degrees))
                .times(Scenarios.flat(DieKind.D6, 5));
        FrozenDie below = new FrozenDie(D6, new Pose(new Vector3(0, inradius, 0), tipped));
        Vector3 up = tipped.rotate(new Vector3(0, 0, 1));
        float lift = 2 * inradius + 0.005f;
        Recording recording = Scenarios.simulate(Scenarios.given(List.of(D6), List.of(Scenarios.still(
                new Vector3(up.x() * lift, inradius + up.y() * lift, up.z() * lift), tipped)), List.of(below)))
                .recording();
        return recording.finalPose(0).position().y();
    }
}
