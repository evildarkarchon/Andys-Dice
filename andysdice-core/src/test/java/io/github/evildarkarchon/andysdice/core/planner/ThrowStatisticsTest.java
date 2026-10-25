package io.github.evildarkarchon.andysdice.core.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.simulation.Attempt;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.ContactMaterial;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.FakeSimulator;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The soak's statistics over planned Rolls (ADR-0013, ADR-0014): Attempts per Throw, time to rest, Cocked
 * Rethrows and Failed Rolls, as the planner reports them over the scripted fake simulator.
 */
class ThrowStatisticsTest {

    private static final TrayGeometry BOX = TrayGeometry.rectangle(15, 10, 3);
    private static final TrayMaterials FELT =
            TrayMaterials.of(new ContactMaterial(0.45f, 0.15f), new ContactMaterial(0.45f, 0.5f), 0.6f);

    /** Plans one Roll of {@code text} through {@code simulator}. */
    private static RollPlan plan(Simulator simulator, String text) {
        return new ThrowPlanner(simulator, BOX, FELT).plan(text, new FaceValueGenerator(new SplittableRandom(7)),
                new SplittableRandom(8));
    }

    /**
     * A simulator whose every Attempt comes to rest at a given time: the fake's Attempt, re-recorded so that its
     * last sample falls at {@code restTime}.
     */
    private static Simulator restingAt(float restTime) {
        FakeSimulator fake = new FakeSimulator();
        return (AttemptRequest request) -> {
            Attempt attempt = fake.simulate(request);
            Recording quick = attempt.recording();
            int samples = Math.round(restTime * Recording.SAMPLE_RATE_HZ) + 1;
            float[] poses = new float[samples * quick.dice() * 7];
            for (int s = 0; s < samples; s++) {
                for (int die = 0; die < quick.dice(); die++) {
                    var pose = quick.finalPose(die);
                    int at = (s * quick.dice() + die) * 7;
                    poses[at] = pose.position().x();
                    poses[at + 1] = pose.position().y();
                    poses[at + 2] = pose.position().z();
                    poses[at + 3] = pose.orientation().x();
                    poses[at + 4] = pose.orientation().y();
                    poses[at + 5] = pose.orientation().z();
                    poses[at + 6] = pose.orientation().w();
                }
            }
            return new Attempt(attempt.ending(), new Recording(quick.dice(), poses, restTime, quick.contacts()),
                    attempt.cockedDice());
        };
    }

    @Test
    void eachRollsThrowCountsItsAttemptsAndTheMeanIsOverThrows() {
        ThrowStatistics stats = new ThrowStatistics();

        stats.add(plan(new FakeSimulator().then(2, Ending.ESCAPED), "2d6"));
        stats.add(plan(new FakeSimulator(), "2d6"));
        stats.add(plan(new FakeSimulator().then(1, Ending.TIMED_OUT).then(2, Ending.COCKED), "2d6"));

        assertThat(stats.throwCount()).isEqualTo(3);
        // 3, 1 and 4 Attempts.
        assertThat(stats.meanAttempts()).isCloseTo(8 / 3.0, within(1e-9));
        assertThat(stats.maxAttempts()).isEqualTo(4);
        assertThat(stats.cockedRethrows()).isZero();
        assertThat(stats.failedRolls()).isZero();
    }

    @Test
    void theP95TimeToRestIsTheNearestRankOverThrows() {
        ThrowStatistics stats = new ThrowStatistics();
        // Twenty Throws resting at 0.1, 0.2, ..., 2.0 s: the 95th percentile by nearest rank is the 19th, 1.9 s.
        for (int i = 1; i <= 20; i++) {
            stats.add(plan(restingAt(i / 10f), "1d6"));
        }

        assertThat(stats.p95RestTime()).isCloseTo(1.9f, within(1e-5f));
        assertThat(stats.meanRestTime()).isCloseTo(1.05, within(1e-5));
    }

    @Test
    void aCockedRethrowIsCountedButIsNotAThrowOfItsOwn() {
        ThrowStatistics stats = new ThrowStatistics();

        stats.add(plan(new FakeSimulator().thenCocked(ThrowPlanner.ATTEMPTS_PER_THROW, 1), "3d6"));

        assertThat(stats.cockedRethrows()).isEqualTo(1);
        assertThat(stats.throwCount()).isEqualTo(1);
        assertThat(stats.meanAttempts()).isEqualTo(ThrowPlanner.ATTEMPTS_PER_THROW);
    }

    @Test
    void aFailedRollIsCountedAndAddsNoThrow() {
        ThrowStatistics stats = new ThrowStatistics();

        stats.add(plan(new FakeSimulator().then(ThrowPlanner.ATTEMPTS_PER_THROW, Ending.ESCAPED), "2d6"));

        assertThat(stats.failedRolls()).isEqualTo(1);
        assertThat(stats.throwCount()).isZero();
    }

    @Test
    void aRerollThrowIsAThrowOfItsOwn() {
        ThrowStatistics stats = new ThrowStatistics();

        // A d6 that shows 1 and is rerolled once: a first Throw, then one Reroll Throw.
        RollPlan roll = new ThrowPlanner(new FakeSimulator(), BOX, FELT).plan("1d6ro1",
                new FaceValueGenerator(new ScriptedRandom(1, 4)), new SplittableRandom(8));
        stats.add(roll);

        assertThat(stats.throwCount()).isEqualTo(2);
    }

    @Test
    void anInvalidExpressionIsNotASoakRoll() {
        ThrowStatistics stats = new ThrowStatistics();
        RollPlan invalid = plan(new FakeSimulator(), "1d7");

        assertThatThrownBy(() -> stats.add(invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyStatisticsHaveNoThrows() {
        ThrowStatistics stats = new ThrowStatistics();

        assertThat(stats.throwCount()).isZero();
        assertThat(stats.meanAttempts()).isNaN();
        assertThat(stats.p95RestTime()).isNaN();
    }
}
